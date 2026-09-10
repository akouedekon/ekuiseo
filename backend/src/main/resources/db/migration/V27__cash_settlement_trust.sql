-- ============================================================
-- V27 : especes comme vrai moyen de paiement, litige especes, niveau de confiance
-- ============================================================
-- bookings.cash_* : reglement du solde en especes a bord (contrat A.6). EXPECTED des que la
--   reservation est confirmee avec un solde a bord (modes CASH et MOMO_DEPOSIT) ; chaque partie
--   confirme apres le depart (POST /bookings/{id}/cash/driver-confirm, /passenger-confirm) ;
--   SETTLED quand les deux confirment, ou une seule sans litige 48 h apres le depart (scheduler,
--   ecriture CASH_ON_BOARD au registre) ; DISPUTED ouvre un signalement CASH_DISPUTE.
-- reports.reason_code : nouveau motif CASH_DISPUTE.
-- users.trips_completed_as_driver : compteur denormalise des trajets termines comme conducteur
--   (TripLifecycleScheduler), pour le niveau de confiance (contrat A.9) sans requete par trajet
--   dans les resultats de recherche.
-- ============================================================

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS cash_status                 VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN IF NOT EXISTS cash_expected_fcfa          BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cash_driver_confirmed_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cash_passenger_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cash_disputed_at            TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cash_dispute_details        TEXT;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS chk_bookings_cash_status;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_cash_status
    CHECK (cash_status IN ('NOT_APPLICABLE', 'EXPECTED', 'DRIVER_CONFIRMED', 'PASSENGER_CONFIRMED', 'SETTLED', 'DISPUTED'));

-- Reservations deja confirmees ou terminees avec un solde a bord : le solde est attendu.
UPDATE bookings
   SET cash_status = 'EXPECTED',
       cash_expected_fcfa = balance_due_on_board
 WHERE status IN ('CONFIRMED', 'COMPLETED')
   AND balance_due_on_board > 0
   AND cash_status = 'NOT_APPLICABLE';

-- Balayage du reglement tacite : une seule confirmation, trajet parti depuis 48 h.
CREATE INDEX IF NOT EXISTS idx_bookings_cash_pending
    ON bookings (trip_id)
    WHERE cash_status IN ('DRIVER_CONFIRMED', 'PASSENGER_CONFIRMED');

-- Motif de signalement CASH_DISPUTE.
ALTER TABLE reports DROP CONSTRAINT IF EXISTS chk_reports_reason_code;
ALTER TABLE reports ADD CONSTRAINT chk_reports_reason_code
    CHECK (reason_code IN ('NO_SHOW', 'DANGEROUS_DRIVING', 'HARASSMENT', 'FRAUD', 'VEHICLE_MISMATCH', 'CASH_DISPUTE', 'OTHER'));

-- Niveau de confiance : trajets termines comme conducteur, denormalises.
ALTER TABLE users ADD COLUMN IF NOT EXISTS trips_completed_as_driver INT NOT NULL DEFAULT 0;
UPDATE users u
   SET trips_completed_as_driver = c.n
  FROM (SELECT driver_id, count(*) AS n FROM trips WHERE status = 'COMPLETED' GROUP BY driver_id) c
 WHERE c.driver_id = u.id
   AND u.trips_completed_as_driver <> c.n;

COMMENT ON COLUMN bookings.cash_status IS
    'Reglement du solde en especes a bord (V27) : NOT_APPLICABLE, EXPECTED, DRIVER_CONFIRMED, PASSENGER_CONFIRMED, SETTLED, DISPUTED.';
COMMENT ON COLUMN users.trips_completed_as_driver IS
    'Trajets termines comme conducteur (V27), maintenu par TripLifecycleScheduler ; niveau de confiance (TrustPolicy).';
