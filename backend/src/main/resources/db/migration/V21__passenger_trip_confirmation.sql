-- ============================================================
-- V21 : confirmation du trajet par le passager
-- ============================================================
-- Jusqu ici, un trajet etait repute effectue 6 h apres le depart sans que personne ne le
-- constate : un conducteur absent etait paye au bout de 24 h, sauf signalement spontane.
-- Le passager peut desormais, apres l heure de depart :
--   - confirmer que le trajet a eu lieu (TRIP_DONE) ;
--   - declarer que le conducteur n est pas venu (DRIVER_NO_SHOW) : la reservation passe au
--     statut DRIVER_NO_SHOW, sort des reversements, et un signalement NO_SHOW est ouvert
--     pour la moderation, qui decide du remboursement.
-- Sans reponse dans les 24 h suivant le depart (delai d eligibilite au reversement), la
-- confirmation est tacite : la reservation reste PENDING et suit le cycle habituel.
-- ============================================================

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS passenger_confirmation VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS passenger_confirmed_at TIMESTAMPTZ;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_passenger_confirmation;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_passenger_confirmation
    CHECK (passenger_confirmation IN ('PENDING', 'TRIP_DONE', 'DRIVER_NO_SHOW'));

-- Nouveau statut de reservation : conducteur absent, declare par le passager.
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_status;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_status
    CHECK (status IN ('PENDING_PAYMENT', 'PENDING_DRIVER_APPROVAL', 'CONFIRMED', 'CANCELLED_BY_PASSENGER',
                      'CANCELLED_BY_DRIVER', 'COMPLETED', 'NO_SHOW', 'DRIVER_NO_SHOW', 'EXPIRED'));

COMMENT ON COLUMN bookings.passenger_confirmation IS
    'Constat du passager apres le depart : PENDING (tacite apres 24 h), TRIP_DONE, DRIVER_NO_SHOW (V21).';
