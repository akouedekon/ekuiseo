-- Ekuiseo - V6 : preferences utilisateur, verification d'identite en tant que
-- ressource dediee (au-dela du simple drapeau users.identity_verified),
-- comptes mobile money enregistres, alertes de recherche enrichies (places,
-- type de trajet), et alignement du vocabulaire des signalements sur le
-- contrat front (voir frontend/src/api/extended.ts).
-- NE JAMAIS modifier V1__init.sql (ni V2-V5) : toute correction passe par une
-- nouvelle migration numerotee.

-- ============================================================
-- USER_PREFERENCES : notifications et preferences a bord (regle metier n.17)
-- ============================================================
CREATE TABLE user_preferences (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    notify_by_push  BOOLEAN NOT NULL DEFAULT TRUE,
    notify_by_sms   BOOLEAN NOT NULL DEFAULT TRUE,
    notify_by_email BOOLEAN NOT NULL DEFAULT FALSE,
    language        VARCHAR(5) NOT NULL DEFAULT 'fr',
    smoking         BOOLEAN NOT NULL DEFAULT FALSE,
    music           BOOLEAN NOT NULL DEFAULT TRUE,
    pets            BOOLEAN NOT NULL DEFAULT FALSE,
    chatty          VARCHAR(20) NOT NULL DEFAULT 'DEPENDS',
    CONSTRAINT chk_user_preferences_chatty CHECK (chatty IN ('QUIET', 'DEPENDS', 'TALKATIVE'))
);

-- ============================================================
-- IDENTITY_VERIFICATIONS : verification d'identite comme ressource dediee
-- (regle metier n.19). Une ligne par utilisateur, mise a jour a chaque nouvelle
-- soumission (jamais dupliquee) ; l'absence de ligne signifie NOT_SUBMITTED.
-- ============================================================
CREATE TABLE identity_verifications (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    document_type     VARCHAR(30),
    document_number   VARCHAR(100),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_at      TIMESTAMPTZ,
    reviewed_at       TIMESTAMPTZ,
    reviewed_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    rejection_reason  TEXT,
    CONSTRAINT chk_identity_verifications_status
        CHECK (status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_identity_verifications_document_type
        CHECK (document_type IS NULL OR document_type IN ('CNI', 'PASSPORT', 'DRIVER_LICENSE'))
);

-- ============================================================
-- PAYMENT_METHODS : comptes mobile money enregistres (regle metier n.18)
-- ============================================================
CREATE TABLE payment_methods (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider    VARCHAR(20) NOT NULL,
    phone       VARCHAR(20) NOT NULL,
    label       VARCHAR(100),
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_methods_provider CHECK (provider IN ('MTN_MOMO', 'MOOV_MONEY', 'CELTIIS_CASH'))
);
CREATE INDEX idx_payment_methods_user ON payment_methods(user_id);
-- Au plus un moyen de paiement par defaut par utilisateur (applique aussi cote
-- service, cet index protege l'invariant au niveau base en cas d'acces concurrent).
CREATE UNIQUE INDEX uq_payment_methods_default ON payment_methods(user_id) WHERE is_default = TRUE;

-- ============================================================
-- SEARCH_ALERTS : places recherchees et type de trajet (contrat front
-- TripAlertRequest/TripAlertResponse, POST /api/v1/trip-alerts)
-- ============================================================
ALTER TABLE search_alerts
    ADD COLUMN seats INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN trip_type VARCHAR(20);
ALTER TABLE search_alerts
    ADD CONSTRAINT chk_search_alerts_trip_type CHECK (trip_type IS NULL OR trip_type IN ('INTERURBAIN', 'QUOTIDIEN'));

-- ============================================================
-- REPORTS : alignement du vocabulaire de statut sur le contrat front (aucune
-- ligne existante attendue - fonctionnalite neuve - mais migration defensive
-- au cas ou des donnees de test auraient deja ete chargees avec les anciens
-- noms REVIEWING/ACTION_TAKEN).
-- ============================================================
UPDATE reports SET status = 'IN_REVIEW' WHERE status = 'REVIEWING';
UPDATE reports SET status = 'RESOLVED' WHERE status = 'ACTION_TAKEN';
ALTER TABLE reports DROP CONSTRAINT IF EXISTS chk_reports_status;
ALTER TABLE reports ADD CONSTRAINT chk_reports_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'));

-- ============================================================
-- Index manquant pour la nouvelle verification "vehicule deja engage sur un
-- trajet a venir" (DELETE /api/v1/me/vehicles/{id}, voir UserService).
-- ============================================================
CREATE INDEX idx_trips_vehicle ON trips(vehicle_id);
