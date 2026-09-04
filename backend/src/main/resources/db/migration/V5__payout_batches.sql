-- Ekuiseo - V5 : detail des lots de reversement conducteurs (regle metier n.12).

ALTER TABLE driver_payouts
    ADD COLUMN period_start TIMESTAMPTZ,
    ADD COLUMN period_end TIMESTAMPTZ;

CREATE TABLE driver_payout_items (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    payout_id   UUID NOT NULL REFERENCES driver_payouts(id) ON DELETE CASCADE,
    booking_id  UUID NOT NULL REFERENCES bookings(id),
    net_amount  BIGINT NOT NULL,
    -- Une reservation ne peut jamais etre incluse dans deux lots de reversement
    -- differents (protection au niveau base, en complement de la requete
    -- BookingRepository#findPayableForDriver qui exclut deja les reservations
    -- deja reversees).
    CONSTRAINT uq_driver_payout_items_booking UNIQUE (booking_id)
);
CREATE INDEX idx_driver_payout_items_payout ON driver_payout_items(payout_id);

-- Accelere le filtre par mode de paiement dans BookingRepository#findPayableForDriver
-- (seules les reservations MoMo, jamais les especes, generent un reversement).
CREATE INDEX idx_bookings_payment_method ON bookings(payment_method);
