-- V16 : phase 2 de l audit, cycle de vie metier (reservations expirees, abonnements,
-- reversements, comptes mobile money, alertes, identite, CGU, export, recherche par arret).
--
-- users.terms_version / terms_accepted_at : acceptation horodatee des CGU (constat F509) ;
--   la version courante est ekuiseo.terms.version, GET /me signale termsAcceptanceRequired.
-- users.last_export_at : dernier export des donnees personnelles (F508), un par 24 h.
-- driver_subscriptions.expiring_notified_at : rappel J-3 envoye une seule fois (F049/F129).
-- driver_payouts : reference du virement, montant effectivement regle, motif d echec,
--   administrateur ayant regle, et contrainte CHECK sur le statut (F133/F458/F302).
--   L unicite driver_payout_items(booking_id) existe deja (uq_driver_payout_items_booking, V5).
-- payment_methods : un meme compte (operateur + numero E.164) une seule fois par utilisateur
--   (F606) ; les doublons existants sont purges en gardant le compte par defaut, sinon le
--   plus ancien.
-- search_alerts.radius_km : rayon de l alerte, renseigne a la creation avec celui de la
--   recherche (F530) ; les alertes sans date sont bornees a 30 jours (F525) ; index partiel
--   pour le matching en une requete (F526).
-- identity_verifications : numero de piece normalise (majuscules, sans espaces) et index de
--   recherche des doublons (F604).
-- trip_stops : index GIST manquant pour la recherche par arret intermediaire (F115/F409).
-- bookings.status EXPIRED (F010/F116/F232) : aucune contrainte CHECK n existe sur cette
--   colonne (V1), rien a etendre ; l index partiel uq_bookings_trip_passenger_active ne le
--   liste pas, ce qui est le comportement voulu.

-- ============================================================
-- USERS : CGU et export
-- ============================================================
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_version     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_export_at    TIMESTAMPTZ;

-- ============================================================
-- DRIVER_SUBSCRIPTIONS : rappel avant echeance
-- ============================================================
ALTER TABLE driver_subscriptions
    ADD COLUMN IF NOT EXISTS expiring_notified_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_driver_subscriptions_active_end
    ON driver_subscriptions (current_period_end) WHERE status = 'ACTIVE';

-- ============================================================
-- DRIVER_PAYOUTS : trace du reglement et statuts controles
-- ============================================================
ALTER TABLE driver_payouts
    ADD COLUMN IF NOT EXISTS external_reference VARCHAR(100),
    ADD COLUMN IF NOT EXISTS settled_amount     BIGINT,
    ADD COLUMN IF NOT EXISTS failure_reason     TEXT,
    ADD COLUMN IF NOT EXISTS settled_by         UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE driver_payouts DROP CONSTRAINT IF EXISTS chk_driver_payouts_status;
ALTER TABLE driver_payouts ADD CONSTRAINT chk_driver_payouts_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'SETTLED', 'FAILED'));
ALTER TABLE driver_payouts DROP CONSTRAINT IF EXISTS chk_driver_payouts_settled_amount;
ALTER TABLE driver_payouts ADD CONSTRAINT chk_driver_payouts_settled_amount
    CHECK (settled_amount IS NULL OR settled_amount >= 0);
CREATE INDEX IF NOT EXISTS idx_driver_payouts_status ON driver_payouts(status);

-- ============================================================
-- PAYMENT_METHODS : un compte par (utilisateur, operateur, numero)
-- ============================================================
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, provider, phone
                              ORDER BY is_default DESC, verified_at NULLS LAST, created_at ASC) AS rn
    FROM payment_methods
)
DELETE FROM payment_methods pm USING ranked r WHERE pm.id = r.id AND r.rn > 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_methods_user_provider_phone
    ON payment_methods (user_id, provider, phone);

-- ============================================================
-- SEARCH_ALERTS : rayon, bornage des alertes sans date, index de matching
-- ============================================================
ALTER TABLE search_alerts
    ADD COLUMN IF NOT EXISTS radius_km DOUBLE PRECISION NOT NULL DEFAULT 15;
ALTER TABLE search_alerts DROP CONSTRAINT IF EXISTS chk_search_alerts_radius;
ALTER TABLE search_alerts ADD CONSTRAINT chk_search_alerts_radius CHECK (radius_km > 0 AND radius_km <= 50);
UPDATE search_alerts SET date_to = (created_at AT TIME ZONE 'Africa/Porto-Novo')::date + 30
    WHERE date_to IS NULL;
CREATE INDEX IF NOT EXISTS idx_search_alerts_active_dates
    ON search_alerts (date_from, date_to) WHERE active = TRUE;

-- ============================================================
-- IDENTITY_VERIFICATIONS : normalisation et recherche des doublons
-- ============================================================
UPDATE identity_verifications
    SET document_number = upper(regexp_replace(document_number, '\s', '', 'g'))
    WHERE document_number IS NOT NULL
      AND document_number <> upper(regexp_replace(document_number, '\s', '', 'g'));
CREATE INDEX IF NOT EXISTS idx_identity_verifications_document
    ON identity_verifications (document_type, upper(document_number));

-- ============================================================
-- TRIP_STOPS : recherche par arret intermediaire
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_trip_stops_point ON trip_stops USING GIST(point);

-- ============================================================
-- OTP_CODES : plafond quotidien par numero (count sur 24 h)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_otp_codes_phone_created ON otp_codes (phone, created_at);

-- ============================================================
-- AUDIT_LOG : filtre par action (GET /api/v1/admin/audit?action=)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);
