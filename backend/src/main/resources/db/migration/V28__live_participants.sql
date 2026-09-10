-- ============================================================
-- V28 : suivi en direct par participant (conducteur ET passagers), flags anti-usurpation,
--       notifications d approche (contrat C de la refonte)
-- ============================================================
-- Jusqu ici, trip_positions ne contenait que les positions du conducteur. Un passager
-- confirme peut desormais partager la sienne avec le conducteur (point de rendez-vous) :
-- chaque ligne porte l utilisateur qui l a envoyee et son role. Les positions existantes
-- sont toutes celles du conducteur du trajet (backfill).
--
-- flags : liste separee par des virgules des anomalies relevees par le serveur a la
-- reception (OUT_OF_AREA, CLOCK_SKEW, TELEPORT, LOW_ACCURACY, voir LocationUpdateService).
-- Conservees pour analyse ; aucune decision automatique n en decoule. NULL = aucune.
--
-- bookings.driver_nearby_notified_at / driver_arrived_notified_at : horodatage des deux
-- notifications DRIVER_NEARBY (< 1 km du point de prise en charge) et DRIVER_ARRIVED
-- (< 150 m), envoyees une seule fois chacune par reservation.
-- ============================================================

ALTER TABLE trip_positions
    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS role    VARCHAR(10),
    ADD COLUMN IF NOT EXISTS flags   VARCHAR(200);

UPDATE trip_positions p
SET user_id = t.driver_id
FROM trips t
WHERE p.trip_id = t.id AND p.user_id IS NULL;

UPDATE trip_positions SET role = 'DRIVER' WHERE role IS NULL;

ALTER TABLE trip_positions ALTER COLUMN role SET NOT NULL;
ALTER TABLE trip_positions ALTER COLUMN role SET DEFAULT 'DRIVER';

ALTER TABLE trip_positions DROP CONSTRAINT IF EXISTS chk_trip_positions_role;
ALTER TABLE trip_positions
    ADD CONSTRAINT chk_trip_positions_role CHECK (role IN ('DRIVER', 'PASSENGER'));

CREATE INDEX IF NOT EXISTS idx_trip_positions_trip_user_recorded
    ON trip_positions (trip_id, user_id, recorded_at DESC);

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS driver_nearby_notified_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS driver_arrived_notified_at TIMESTAMPTZ;

COMMENT ON COLUMN trip_positions.user_id IS
    'Participant qui a envoye la position (conducteur ou passager confirme), V28.';
COMMENT ON COLUMN trip_positions.role IS
    'DRIVER ou PASSENGER (V28).';
COMMENT ON COLUMN trip_positions.flags IS
    'Anomalies relevees a la reception, separees par des virgules (OUT_OF_AREA, CLOCK_SKEW, TELEPORT, LOW_ACCURACY), V28.';
COMMENT ON COLUMN bookings.driver_nearby_notified_at IS
    'Notification DRIVER_NEARBY envoyee (conducteur a moins de 1 km du point de prise en charge), V28.';
COMMENT ON COLUMN bookings.driver_arrived_notified_at IS
    'Notification DRIVER_ARRIVED envoyee (conducteur a moins de 150 m du point de prise en charge), V28.';
