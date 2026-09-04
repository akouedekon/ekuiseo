-- Ekuiseo - V4 : abonnement conducteur (regle metier n.11 : 2000 FCFA/mois,
-- commission ramenee a 0%) et son paiement (reutilise la table payments, qui
-- doit donc pouvoir regler soit une reservation, soit un abonnement, jamais les
-- deux a la fois).

CREATE TABLE driver_subscriptions (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    driver_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    price_fcfa          BIGINT NOT NULL CHECK (price_fcfa >= 0),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT',
    started_at          TIMESTAMPTZ,
    current_period_end  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_driver_subscriptions_status
        CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'EXPIRED', 'CANCELLED'))
);
CREATE INDEX idx_driver_subscriptions_driver ON driver_subscriptions(driver_id);
-- Au plus un abonnement ACTIVE a la fois par conducteur (verifie aussi
-- applicativement dans DriverSubscriptionRepository/SubscriptionService, cet
-- index protege l'invariant au niveau base en cas d'acces concurrent).
CREATE UNIQUE INDEX uq_driver_subscriptions_active ON driver_subscriptions(driver_id) WHERE status = 'ACTIVE';

-- ============================================================
-- PAYMENTS : autoriser un paiement d'abonnement (sans reservation associee)
-- ============================================================
ALTER TABLE payments ALTER COLUMN booking_id DROP NOT NULL;
ALTER TABLE payments ADD COLUMN subscription_id UUID REFERENCES driver_subscriptions(id);
ALTER TABLE payments ADD CONSTRAINT chk_payments_target CHECK (
    (booking_id IS NOT NULL AND subscription_id IS NULL)
    OR (booking_id IS NULL AND subscription_id IS NOT NULL)
);
CREATE INDEX idx_payments_subscription ON payments(subscription_id);
