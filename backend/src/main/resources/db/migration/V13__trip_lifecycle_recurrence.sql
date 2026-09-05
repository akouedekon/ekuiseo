-- V13 : lot 1.3 de l audit (cycle de vie des trajets, mode quotidien).
--
-- trips.status TEMPLATE : le trajet « parent » d une navette quotidienne est un modele,
--   jamais cherchable ni reservable ; seules ses occurrences (parent_trip_id) le sont.
--   Les parents existants passent TEMPLATE (leurs reservations eventuelles restent
--   valides et seront cloturees par le scheduler de cycle de vie).
-- uq_trips_parent_departure : une occurrence par parent et par instant de depart, meme
--   sous concurrence (generation a la creation + tache nocturne).
-- bookings.free_cancellation_until : fenetre d annulation gratuite ouverte au passager
--   quand le conducteur modifie l horaire d un trajet reserve.
-- Index partiels pour le scheduler de cycle de vie (trajets a passer ONGOING/COMPLETED,
--   reservations a cloturer).
UPDATE trips SET status = 'TEMPLATE'
    WHERE recurrence_rule IS NOT NULL AND parent_trip_id IS NULL AND status IN ('PUBLISHED', 'FULL', 'DRAFT');

CREATE UNIQUE INDEX IF NOT EXISTS uq_trips_parent_departure
    ON trips (parent_trip_id, departure_at) WHERE parent_trip_id IS NOT NULL;

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS free_cancellation_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_trips_lifecycle ON trips (departure_at)
    WHERE status IN ('PUBLISHED', 'FULL', 'ONGOING');
CREATE INDEX IF NOT EXISTS idx_bookings_status_trip ON bookings (status, trip_id);
