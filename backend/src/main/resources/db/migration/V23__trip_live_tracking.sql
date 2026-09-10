-- ============================================================
-- V23 : suivi en direct d un trajet (« live tracking »)
-- ============================================================
-- Le conducteur partage sa position pendant le trajet ; ses passagers confirmes la voient
-- sur la carte du trajet (derniere mise a jour, distance restante, arrivee estimee), et un
-- lien public a jeton permet a un proche du passager de suivre le vehicule sans compte
-- (partage WhatsApp). Le modele reste celui de trajets planifies : pas de course a la
-- demande, la position n est acceptee que d une heure avant le depart jusqu a la fin.
--
-- trip_positions : une ligne par position recue (POST /api/v1/trips/{id}/live/positions,
-- au plus toutes les 10 s cote client, 120/min cote serveur). Seule la derniere sert a
-- l affichage ; l historique n est conserve que 24 h (RetentionScheduler,
-- ekuiseo.retention.trip-positions-hours, docs/CONFORMITE.md 3.2) et disparait avec le
-- trajet. Donnee de localisation : aucune exploitation au-dela du suivi du trajet.
--
-- trips.live_sharing_enabled : interrupteur du conducteur ; live_share_token : jeton du
-- lien public (/live/{token}), genere a la premiere activation, efface par la purge
-- nocturne une fois le trajet termine ou annule ; last_position_at : horodatage de la
-- derniere position, pour afficher la fraicheur sans lire trip_positions.
-- ============================================================

CREATE TABLE trip_positions (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id      UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    heading      REAL,
    speed_kmh    REAL,
    accuracy_m   REAL,
    recorded_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_trip_positions_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT chk_trip_positions_lng CHECK (lng BETWEEN -180 AND 180)
);

CREATE INDEX idx_trip_positions_trip_recorded ON trip_positions (trip_id, recorded_at DESC);

ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS live_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS live_share_token VARCHAR(64) UNIQUE,
    ADD COLUMN IF NOT EXISTS last_position_at TIMESTAMPTZ;

COMMENT ON TABLE trip_positions IS
    'Positions du vehicule pendant un trajet (suivi en direct, V23) ; conservees 24 h.';
COMMENT ON COLUMN trips.live_sharing_enabled IS
    'Partage de position active par le conducteur (V23).';
COMMENT ON COLUMN trips.live_share_token IS
    'Jeton du lien public de suivi /live/{token} (V23) ; efface a la purge nocturne apres la fin du trajet.';
COMMENT ON COLUMN trips.last_position_at IS
    'Horodatage de la derniere position recue (V23).';
