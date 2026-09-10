-- ============================================================
-- V26 : socle financier du lot A (refonte paiements)
-- ============================================================
-- 1. idempotency_keys : rejeu d une reponse memorisee pour les ecritures financieres
--    (reservation, acompte, confirmation widget, annulation) portant un en-tete
--    Idempotency-Key ; purgees apres 24 h (PaymentHousekeepingScheduler).
-- 2. refunds : machine d etat des remboursements (REQUESTED -> PROCESSING -> SUCCEEDED |
--    FAILED | MANUAL_REVIEW ; FAILED -> PROCESSING en reprise ; MANUAL_REVIEW -> SUCCEEDED
--    par l administration). Un seul remboursement « vivant » par paiement (index unique
--    partiel). Les paiements deja REFUND_PENDING / REFUND_MANUAL / REFUNDED recoivent leur
--    ligne. payments.status reste synchronise (rien ne casse pour le front existant).
-- 3. ledger_entries : registre financier en AJOUT SEUL (aucune mise a jour ni suppression,
--    garanti par un trigger) : ce que le passager a paye, les frais de l agregateur, la
--    commission, la part du conducteur, les remboursements et leurs contre-passations, les
--    reversements, les especes reglees a bord, les corrections d administration.
-- 4. payment_events : une ligne par transition ou tentative sur un paiement (widget,
--    webhook, scheduler, admin, systeme), y compris les refus.
-- 5. payment_webhook_events : chaque webhook recu est persiste AVANT traitement, avec le
--    hash de son corps ; un rejeu du meme corps deja traite est enregistre DUPLICATE et
--    jamais retraite ; une signature invalide est enregistree REJECTED.
-- 6. reconciliation_runs / reconciliation_anomalies : rapprochement Ekuiseo / agregateur
--    (re-verification quotidienne des paiements recents, import de l export CSV du
--    fournisseur), anomalies a traiter par le back-office.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Idempotence
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key             VARCHAR(64) NOT NULL,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route           VARCHAR(200) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    -- NULL tant que la requete est en cours : un second appel simultane recoit 409.
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_keys UNIQUE (key, user_id, route)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_created ON idempotency_keys (created_at);

-- ------------------------------------------------------------
-- 2. Remboursements
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refunds (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    payment_id         UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    booking_id         UUID REFERENCES bookings(id) ON DELETE SET NULL,
    amount_fcfa        BIGINT NOT NULL,
    kind               VARCHAR(10) NOT NULL,
    reason             VARCHAR(60) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    attempts           INT NOT NULL DEFAULT 0,
    last_error         VARCHAR(500),
    provider_reference VARCHAR(100),
    -- NULL = decision du systeme (annulation, paiement orphelin, echeance) ; sinon l administrateur.
    requested_by       UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    CONSTRAINT chk_refunds_amount CHECK (amount_fcfa > 0),
    CONSTRAINT chk_refunds_kind CHECK (kind IN ('FULL', 'PARTIAL')),
    CONSTRAINT chk_refunds_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW'))
);
-- Un seul remboursement vivant par paiement : un FAILED (abandonne ou en attente de reprise)
-- ne bloque pas, tout autre statut oui.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_live_payment ON refunds (payment_id) WHERE status <> 'FAILED';
CREATE INDEX IF NOT EXISTS idx_refunds_status_created ON refunds (status, created_at);
CREATE INDEX IF NOT EXISTS idx_refunds_booking ON refunds (booking_id);

-- Reprise des remboursements deja decides avant cette migration (idempotent).
INSERT INTO refunds (payment_id, booking_id, amount_fcfa, kind, reason, status, attempts, last_error,
                     provider_reference, created_at, updated_at, completed_at)
SELECT p.id,
       p.booking_id,
       coalesce(p.refund_amount, p.amount),
       CASE WHEN coalesce(p.refund_amount, p.amount) < p.amount THEN 'PARTIAL' ELSE 'FULL' END,
       coalesce(p.refund_reason, 'MIGRATION_V26'),
       CASE p.status
           WHEN 'REFUND_PENDING' THEN 'REQUESTED'
           WHEN 'REFUND_MANUAL' THEN 'MANUAL_REVIEW'
           ELSE 'SUCCEEDED'
       END,
       p.refund_attempts,
       p.refund_last_error,
       NULL,
       coalesce(p.refund_requested_at, p.updated_at, p.created_at),
       now(),
       p.refunded_at
FROM payments p
WHERE p.status IN ('REFUND_PENDING', 'REFUND_MANUAL', 'REFUNDED')
  AND coalesce(p.refund_amount, p.amount) > 0
  AND NOT EXISTS (SELECT 1 FROM refunds r WHERE r.payment_id = p.id);

-- ------------------------------------------------------------
-- 3. Registre financier (ajout seul)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger_entries (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    entry_type         VARCHAR(30) NOT NULL,
    account            VARCHAR(20) NOT NULL,
    direction          VARCHAR(6) NOT NULL,
    amount_fcfa        BIGINT NOT NULL,
    currency           VARCHAR(3) NOT NULL DEFAULT 'XOF',
    booking_id         UUID REFERENCES bookings(id),
    payment_id         UUID REFERENCES payments(id),
    refund_id          UUID REFERENCES refunds(id),
    payout_id          UUID REFERENCES driver_payouts(id),
    user_id            UUID REFERENCES users(id),
    provider           VARCHAR(20),
    provider_reference VARCHAR(100),
    description        VARCHAR(500),
    -- NULL = ecriture du systeme ; sinon l administrateur (ADJUSTMENT).
    created_by         UUID,
    CONSTRAINT chk_ledger_entries_type CHECK (entry_type IN (
        'PASSENGER_PAYMENT', 'PROVIDER_FEE', 'PLATFORM_COMMISSION', 'DRIVER_SHARE', 'REFUND',
        'COMMISSION_REVERSAL', 'DRIVER_SHARE_REVERSAL', 'PAYOUT', 'CASH_ON_BOARD', 'ADJUSTMENT')),
    CONSTRAINT chk_ledger_entries_account CHECK (account IN ('PASSENGER', 'PLATFORM', 'DRIVER', 'PROVIDER')),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount CHECK (amount_fcfa > 0)
);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_created ON ledger_entries (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_booking ON ledger_entries (booking_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_payment ON ledger_entries (payment_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_user ON ledger_entries (user_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_type_created ON ledger_entries (entry_type, created_at);

-- Ajout seul : toute tentative de mise a jour ou de suppression d une ecriture echoue en
-- base, quel que soit le chemin (application, DBeaver, script). Une erreur se corrige par
-- une ecriture ADJUSTMENT, jamais en reecrivant l histoire.
CREATE OR REPLACE FUNCTION ledger_entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries est en ajout seul : % interdit (corriger par une ecriture ADJUSTMENT)', TG_OP;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_ledger_entries_append_only ON ledger_entries;
CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_append_only();

-- ------------------------------------------------------------
-- 4. Evenements de paiement
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_events (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    payment_id  UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    event_type  VARCHAR(40) NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20),
    source      VARCHAR(10) NOT NULL,
    actor_id    UUID,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_events_source CHECK (source IN ('WIDGET', 'WEBHOOK', 'SCHEDULER', 'ADMIN', 'SYSTEM'))
);
CREATE INDEX IF NOT EXISTS idx_payment_events_payment ON payment_events (payment_id, created_at);

-- ------------------------------------------------------------
-- 5. Webhooks recus
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_webhook_events (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider        VARCHAR(20) NOT NULL,
    provider_tx_id  VARCHAR(100),
    payload         JSONB,
    payload_hash    VARCHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    -- RECEIVED = persiste, traitement en cours (etat transitoire).
    outcome         VARCHAR(10) NOT NULL,
    error           VARCHAR(500),
    CONSTRAINT chk_payment_webhook_events_outcome CHECK (outcome IN ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'IGNORED', 'REJECTED', 'ERROR'))
);
-- Un corps donne n est traite qu une fois ; ses rejeux sont des lignes DUPLICATE.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_webhook_events_hash
    ON payment_webhook_events (provider, payload_hash) WHERE outcome <> 'DUPLICATE';
CREATE INDEX IF NOT EXISTS idx_payment_webhook_events_received ON payment_webhook_events (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_webhook_events_tx ON payment_webhook_events (provider_tx_id);

-- ------------------------------------------------------------
-- 6. Rapprochement
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- « trigger » est un mot-cle SQL : colonne nommee trigger_type, exposee « trigger » dans l API.
    trigger_type    VARCHAR(10) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(10) NOT NULL,
    checked         INT NOT NULL DEFAULT 0,
    anomalies_found INT NOT NULL DEFAULT 0,
    notes           TEXT,
    started_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_reconciliation_runs_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'IMPORT')),
    CONSTRAINT chk_reconciliation_runs_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_started ON reconciliation_runs (started_at DESC);

CREATE TABLE IF NOT EXISTS reconciliation_anomalies (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id          UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    kind            VARCHAR(30) NOT NULL,
    payment_id      UUID REFERENCES payments(id) ON DELETE CASCADE,
    booking_id      UUID,
    provider_tx_id  VARCHAR(100),
    expected        JSONB,
    observed        JSONB,
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    resolution_note TEXT,
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_reconciliation_anomalies_kind CHECK (kind IN (
        'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'MISSING_AT_PROVIDER', 'UNKNOWN_AT_PROVIDER',
        'DUPLICATE_PROVIDER_TX', 'REFUND_MISSING', 'LEDGER_IMBALANCE')),
    CONSTRAINT chk_reconciliation_anomalies_status CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED'))
);
CREATE INDEX IF NOT EXISTS idx_reconciliation_anomalies_status ON reconciliation_anomalies (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reconciliation_anomalies_payment ON reconciliation_anomalies (payment_id);

COMMENT ON TABLE ledger_entries IS
    'Registre financier en ajout seul (V26). Equilibre d un paiement : PASSENGER_PAYMENT = PLATFORM_COMMISSION + DRIVER_SHARE ; PROVIDER_FEE est un cout de la plateforme, preleve sur sa commission.';
COMMENT ON TABLE refunds IS
    'Machine d etat des remboursements (V26) ; payments.status reste synchronise (REFUND_PENDING / REFUND_MANUAL / REFUNDED).';
