-- V19 : validation conducteur d une reservation (point n.13 de l audit, constat F048).
--
-- trips.instant_booking reprend un sens : TRUE (defaut) = reservation confirmee des l acompte
-- (comportement historique) ; FALSE = le conducteur doit accepter chaque passager.
--
-- bookings.status gagne PENDING_DRIVER_APPROVAL : acompte encaisse (ou especes), places
-- decrementees, en attente de l accord du conducteur. Le CHECK pose en V17 est recree.
-- bookings.approval_deadline_at : echeance de la reponse du conducteur
-- (ekuiseo.booking.driver-approval-hours, plafonnee a 2 h avant le depart) ; passe ce delai,
-- la reservation est traitee comme un refus (remboursement integral, places liberees).
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS approval_deadline_at TIMESTAMPTZ;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_status;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_status
    CHECK (status IN ('PENDING_PAYMENT', 'PENDING_DRIVER_APPROVAL', 'CONFIRMED', 'CANCELLED_BY_PASSENGER',
                      'CANCELLED_BY_DRIVER', 'COMPLETED', 'NO_SHOW', 'EXPIRED'));

-- Balayage du scheduler (BookingService#expireStaleApprovals) : seules les demandes en attente.
CREATE INDEX IF NOT EXISTS idx_bookings_pending_approval
    ON bookings (approval_deadline_at) WHERE status = 'PENDING_DRIVER_APPROVAL';
