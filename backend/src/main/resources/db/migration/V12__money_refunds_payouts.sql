-- V12 : lot 1.2 de l audit (argent).
--
-- payments : suivi des remboursements. Les statuts REFUND_PENDING (a executer chez
--   Kkiapay, hors transaction, avec reprise) et REFUND_MANUAL (partiel, ou echec
--   definitif, ou paiement sans identifiant Kkiapay) s ajoutent a INITIATED /
--   SUCCEEDED / FAILED / REFUNDED. refund_amount et refund_reason documentent la
--   demande ; refund_attempts / refund_last_error tracent les tentatives.
-- bookings.expires_at : echeance de l acompte (remplace created_at + 20 min), prolongee
--   quand un paiement est initie juste avant la limite pour que le widget USSD et le
--   scheduler d expiration ne se croisent plus.
-- driver_payouts.destination_provider : operateur fige au moment du lot, lu depuis le
--   compte mobile money verifie par defaut du conducteur (plus jamais le numero de
--   connexion) ; destination_msisdn devient nullable (lot sans compte = exclu du lot).
-- driver_payout_items.reversed_at : reservation remboursee apres inclusion dans un lot
--   deja traite (PROCESSING/SETTLED) : l ajustement est trace, pas silencieux.
-- payment_methods.verified_at : un compte n est destination de reversement qu une fois
--   sa possession etablie (numero de connexion, ou validation admin journalisee).
-- Prix : un trajet ou un arret ne peut plus etre a 0 F (verifie sans violation en
--   production le 2026-09-05 avant cette migration).
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS refund_amount       BIGINT,
    ADD COLUMN IF NOT EXISTS refund_reason       VARCHAR(60),
    ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_attempts     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_last_error   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS refunded_at         TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
UPDATE bookings SET expires_at = created_at + interval '20 minutes'
    WHERE status = 'PENDING_PAYMENT' AND expires_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_bookings_pending_expiry ON bookings(expires_at) WHERE status = 'PENDING_PAYMENT';

ALTER TABLE driver_payouts
    ADD COLUMN IF NOT EXISTS destination_provider VARCHAR(20),
    ALTER COLUMN destination_msisdn DROP NOT NULL;
ALTER TABLE driver_payouts ADD CONSTRAINT chk_driver_payouts_provider
    CHECK (destination_provider IS NULL OR destination_provider IN ('MTN_MOMO', 'MOOV_MONEY', 'CELTIIS_CASH'));

ALTER TABLE driver_payout_items
    ADD COLUMN IF NOT EXISTS reversed_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(60);

ALTER TABLE payment_methods ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;
UPDATE payment_methods pm SET verified_at = now()
    FROM users u WHERE pm.user_id = u.id AND pm.phone = u.phone AND pm.verified_at IS NULL;

ALTER TABLE trips ADD CONSTRAINT chk_trips_price_positive CHECK (price_per_seat > 0);
ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_price_positive CHECK (price_from_origin > 0);
