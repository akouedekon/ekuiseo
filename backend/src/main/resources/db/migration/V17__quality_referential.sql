-- V17 : phase 3 backend (qualite) et phase 5 (referentiel de lieux, KPI de retention).
--
-- payments.verified_amount : montant reellement verifie chez Kkiapay (F151) ; un surpaiement est
--   journalise (PAYMENT_OVERPAID), jamais rembourse automatiquement.
-- payments.updated_at : vrai horodatage de mise a jour pour PaymentStatusResponse.updatedAt (F501).
-- users.last_login_at : derniere verification de code reussie (F541/F544), exposee au back-office.
-- users.password_hash : plus obligatoire depuis le parcours OTP seul (F148) ; NULL pour les
--   nouveaux comptes, les hachages factices existants sont conserves (jamais lus).
-- Index (F145) : notifications par utilisateur (liste et badge non lu), reservations en attente
--   par date de creation, reservations d un passager par date, abonnement actif par conducteur,
--   paiements INITIATED par date (menage des paiements abandonnes, F019).
-- Contraintes CHECK (F146) sur les statuts stockes en VARCHAR, alignees sur les enums Java
--   (UserStatus, TripStatus, BookingStatus, PaymentStatus, SubscriptionStatus) ; driver_payouts en a
--   deja une depuis V16.
-- geo_places (point n.11, F413/F421/F422) : kind STATION (gares routieres et carrefours), alias
--   normalises (text[]), extension pg_trgm et index trigramme pour la tolerance aux fautes, villes,
--   quartiers et gares manquants inseres de facon idempotente (WHERE NOT EXISTS sur nom + kind).
-- search_alert_matches (F533) : une seule notification par (alerte, trajet ou modele de navette).
-- identity_verifications (F515) : numero de piece reduit a ses 4 derniers caracteres sur les
--   dossiers deja decides ; le service fait de meme a chaque decision.

-- ============================================================
-- PAYMENTS
-- ============================================================
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS verified_amount BIGINT,
    ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMPTZ;
UPDATE payments SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE payments ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE payments ALTER COLUMN updated_at SET DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_payments_initiated_created ON payments (created_at) WHERE status = 'INITIATED';

-- ============================================================
-- USERS
-- ============================================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- ============================================================
-- INDEX (F145)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_bookings_pending_created ON bookings (created_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX IF NOT EXISTS idx_bookings_passenger_created ON bookings (passenger_id, created_at);
CREATE INDEX IF NOT EXISTS idx_driver_subscriptions_driver_active
    ON driver_subscriptions (driver_id, current_period_end) WHERE status = 'ACTIVE';

-- ============================================================
-- CONTRAINTES CHECK SUR LES STATUTS (F146)
-- ============================================================
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DELETED'));

ALTER TABLE trips DROP CONSTRAINT IF EXISTS chk_trips_status;
ALTER TABLE trips ADD CONSTRAINT chk_trips_status
    CHECK (status IN ('DRAFT', 'TEMPLATE', 'PUBLISHED', 'FULL', 'ONGOING', 'COMPLETED', 'CANCELLED'));

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_status;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_status
    CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED_BY_PASSENGER', 'CANCELLED_BY_DRIVER',
                      'COMPLETED', 'NO_SHOW', 'EXPIRED'));

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payments_status;
ALTER TABLE payments ADD CONSTRAINT chk_payments_status
    CHECK (status IN ('INITIATED', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_MANUAL'));

ALTER TABLE driver_subscriptions DROP CONSTRAINT IF EXISTS chk_driver_subscriptions_status;
ALTER TABLE driver_subscriptions ADD CONSTRAINT chk_driver_subscriptions_status
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'EXPIRED', 'CANCELLED'));

-- ============================================================
-- SEARCH_ALERT_MATCHES : dedoublonnage des notifications d alerte (F533)
-- ============================================================
CREATE TABLE IF NOT EXISTS search_alert_matches (
    alert_id    UUID NOT NULL REFERENCES search_alerts(id) ON DELETE CASCADE,
    trip_key    UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (alert_id, trip_key)
);

-- ============================================================
-- IDENTITY_VERIFICATIONS : troncature des dossiers deja decides (F515)
-- ============================================================
UPDATE identity_verifications
    SET document_number = '****' || right(document_number, 4)
    WHERE status IN ('APPROVED', 'REJECTED')
      AND document_number IS NOT NULL
      AND length(document_number) > 4
      AND document_number NOT LIKE '****%';

-- ============================================================
-- GEO_PLACES : gares (STATION), alias, trigrammes
-- ============================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE geo_places DROP CONSTRAINT IF EXISTS chk_geo_places_kind;
ALTER TABLE geo_places ADD CONSTRAINT chk_geo_places_kind CHECK (kind IN ('CITY', 'DISTRICT', 'STATION'));
-- Alias deja normalises (minuscules, sans accents), compares tels quels par GeoPlaceRepository#search.
ALTER TABLE geo_places ADD COLUMN IF NOT EXISTS aliases TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_geo_places_normalized_trgm ON geo_places USING gin (normalized_name gin_trgm_ops);

-- Villes manquantes (kind CITY). Coordonnees approchees au 1/10 000e de degre, comme en V3.
INSERT INTO geo_places (name, normalized_name, region, country_code, kind, lat, lng, aliases)
SELECT v.name, unaccent(lower(v.name)), v.region, 'BJ', 'CITY', v.lat, v.lng, v.aliases
FROM (VALUES
    ('Sèmè-Kpodji',  'Ouémé',      6.3700,  2.6200, ARRAY['seme-podji', 'seme podji', 'seme kpodji', 'seme']),
    ('Allada',       'Atlantique', 6.6653,  2.1514, ARRAY[]::text[]),
    ('Dassa-Zoumè',  'Collines',   7.7500,  2.1833, ARRAY['dassa', 'dassa zoume']),
    ('Savè',         'Collines',   8.0333,  2.4833, ARRAY[]::text[]),
    ('Covè',         'Zou',        7.2211,  2.3397, ARRAY[]::text[]),
    ('Tchaourou',    'Borgou',     8.8833,  2.6000, ARRAY[]::text[]),
    ('Aplahoué',     'Couffo',     6.9333,  1.6833, ARRAY[]::text[]),
    ('Tanguiéta',    'Atacora',   10.6211,  1.2622, ARRAY[]::text[]),
    ('Nikki',        'Borgou',     9.9400,  3.2108, ARRAY[]::text[]),
    ('Pobè',         'Plateau',    6.9800,  2.6650, ARRAY[]::text[]),
    ('Kétou',        'Plateau',    7.3583,  2.6067, ARRAY[]::text[]),
    ('Sakété',       'Plateau',    6.7361,  2.6583, ARRAY[]::text[]),
    ('Ouidah',       'Atlantique', 6.3626,  2.0852, ARRAY[]::text[]),
    ('Lokossa',      'Mono',       6.6389,  1.7169, ARRAY[]::text[]),
    ('Comè',         'Mono',       6.4056,  1.8836, ARRAY[]::text[]),
    ('Grand-Popo',   'Mono',       6.2833,  1.8167, ARRAY['grand popo']),
    ('Athiémé',      'Mono',       6.5833,  1.6667, ARRAY[]::text[]),
    ('Dogbo',        'Couffo',     6.7994,  1.7817, ARRAY['dogbo-tota']),
    ('Djougou',      'Donga',      9.7085,  1.6663, ARRAY[]::text[]),
    ('Kandi',        'Alibori',   11.1342,  2.9386, ARRAY[]::text[]),
    ('Malanville',   'Alibori',   11.8636,  3.3862, ARRAY[]::text[]),
    ('Savalou',      'Collines',   7.9285,  1.9739, ARRAY[]::text[]),
    ('Glazoué',      'Collines',   7.9736,  2.2394, ARRAY[]::text[]),
    ('Bassila',      'Donga',      9.0167,  1.6667, ARRAY[]::text[]),
    ('Kérou',        'Atacora',   10.8167,  2.1000, ARRAY[]::text[]),
    ('Banikoara',    'Alibori',   11.2986,  2.4386, ARRAY[]::text[]),
    ('Ségbana',      'Alibori',   10.9269,  3.6944, ARRAY[]::text[])
) AS v(name, region, lat, lng, aliases)
WHERE NOT EXISTS (
    SELECT 1 FROM geo_places g WHERE g.normalized_name = unaccent(lower(v.name)) AND g.kind = 'CITY'
);

-- Alias des villes deja presentes (V3).
UPDATE geo_places SET aliases = ARRAY['calavi', 'abomey calavi']
    WHERE kind = 'CITY' AND normalized_name = 'abomey-calavi' AND aliases = '{}';
UPDATE geo_places SET aliases = ARRAY['porto novo', 'hogbonou', 'adjatche']
    WHERE kind = 'CITY' AND normalized_name = 'porto-novo' AND aliases = '{}';
UPDATE geo_places SET aliases = ARRAY['nati']
    WHERE kind = 'CITY' AND normalized_name = 'natitingou' AND aliases = '{}';

-- Quartiers et arrondissements (kind DISTRICT), rattaches a leur ville.
INSERT INTO geo_places (name, normalized_name, region, country_code, kind, parent_place_id, lat, lng)
SELECT v.name, unaccent(lower(v.name)), c.region, 'BJ', 'DISTRICT', c.id, v.lat, v.lng
FROM (VALUES
    ('Abomey-Calavi', 'Togoudo',       6.4550, 2.3520),
    ('Abomey-Calavi', 'Tankpè',        6.4300, 2.3400),
    ('Abomey-Calavi', 'Zogbadjè',      6.4600, 2.3450),
    ('Abomey-Calavi', 'Kpota',         6.4700, 2.3600),
    ('Abomey-Calavi', 'Hêvié',         6.4150, 2.2700),
    ('Abomey-Calavi', 'Akassato',      6.5000, 2.3600),
    ('Abomey-Calavi', 'Godomey',       6.3900, 2.3200),
    ('Porto-Novo',    'Ouando',        6.4850, 2.6420),
    ('Porto-Novo',    'Djègan-Kpèvi',  6.4900, 2.6150),
    ('Porto-Novo',    'Tokpota',       6.5000, 2.6350),
    ('Parakou',       'Zongo',         9.3400, 2.6250),
    ('Parakou',       'Banikanni',     9.3600, 2.6250),
    ('Parakou',       'Albarika',      9.3300, 2.6450)
) AS v(city, name, lat, lng)
JOIN geo_places c ON c.kind = 'CITY' AND c.normalized_name = unaccent(lower(v.city))
WHERE NOT EXISTS (
    SELECT 1 FROM geo_places g WHERE g.normalized_name = unaccent(lower(v.name)) AND g.kind = 'DISTRICT'
);

-- Gares routieres et carrefours (kind STATION), points de rendez-vous reels.
INSERT INTO geo_places (name, normalized_name, region, country_code, kind, parent_place_id, lat, lng, aliases)
SELECT v.name, unaccent(lower(v.name)), c.region, 'BJ', 'STATION', c.id, v.lat, v.lng, v.aliases
FROM (VALUES
    ('Cotonou',       'Jonquet',           6.3620, 2.4180, ARRAY['gare jonquet']),
    ('Cotonou',       'Étoile Rouge',      6.3730, 2.4060, ARRAY['etoile rouge', 'place de l etoile rouge']),
    ('Cotonou',       'Missèbo',           6.3670, 2.4240, ARRAY['missebo', 'marche missebo']),
    ('Cotonou',       'Tokpa',             6.3700, 2.4300, ARRAY['dantokpa', 'marche dantokpa']),
    ('Abomey-Calavi', 'Gare de Calavi',    6.4450, 2.3550, ARRAY['gare calavi', 'calavi gare']),
    ('Abomey-Calavi', 'Carrefour Tankpè',  6.4310, 2.3410, ARRAY['carrefour tankpe', 'tankpe carrefour']),
    ('Porto-Novo',    'Gare d''Ouando',    6.4860, 2.6410, ARRAY['gare ouando', 'ouando gare']),
    ('Parakou',       'Gare de Parakou',   9.3450, 2.6300, ARRAY['gare parakou', 'gare routiere parakou'])
) AS v(city, name, lat, lng, aliases)
JOIN geo_places c ON c.kind = 'CITY' AND c.normalized_name = unaccent(lower(v.city))
WHERE NOT EXISTS (
    SELECT 1 FROM geo_places g WHERE g.normalized_name = unaccent(lower(v.name)) AND g.kind = 'STATION'
);
