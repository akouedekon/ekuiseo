-- Ekuiseo - V2 : roles, moderation (signalements), journal d'audit,
-- statistiques conducteur, rappel de trajet, tentatives OTP.
-- NE JAMAIS modifier V1__init.sql : toute correction de schema passe par une
-- nouvelle migration numerotee, y compris pour des tables deja creees en V1.

-- ============================================================
-- USERS : role back-office, statistiques conducteur, suspension, Web Push
-- ============================================================
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD COLUMN late_cancellations_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN suspended_reason TEXT,
    -- Abonnement Web Push du navigateur (endpoint + cles), colonne preparee pour une
    -- future implementation Web Push (non exploitee pour l'instant, voir README).
    ADD COLUMN push_subscription JSONB;

ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

CREATE INDEX idx_users_role ON users(role);

-- ============================================================
-- OTP_CODES : nombre de tentatives de verification (regle metier n.8)
-- ============================================================
ALTER TABLE otp_codes
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

-- ============================================================
-- TRIPS : rappel "la veille du depart" (regle metier n.10)
-- ============================================================
ALTER TABLE trips
    ADD COLUMN reminder_sent_at TIMESTAMPTZ;

-- Accelere TripRepository#findDueForReminder (execute chaque heure par TripReminderScheduler) :
-- ne cible que les trajets publies pas encore rappeles, sur la colonne de tri departure_at.
CREATE INDEX idx_trips_reminder_pending ON trips(departure_at)
    WHERE status = 'PUBLISHED' AND reminder_sent_at IS NULL;

-- ============================================================
-- REPORTS : signalements d'utilisateurs ou de trajets (regle metier n.15)
-- ============================================================
CREATE TABLE reports (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reporter_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_user_id    UUID REFERENCES users(id) ON DELETE CASCADE,
    reported_trip_id    UUID REFERENCES trips(id) ON DELETE CASCADE,
    reason_code         VARCHAR(50) NOT NULL,
    details             TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution_note     TEXT,
    resolved_by         UUID REFERENCES users(id),
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_reports_target CHECK (
        (reported_user_id IS NOT NULL AND reported_trip_id IS NULL)
        OR (reported_user_id IS NULL AND reported_trip_id IS NOT NULL)
    ),
    CONSTRAINT chk_reports_status CHECK (status IN ('OPEN', 'REVIEWING', 'ACTION_TAKEN', 'DISMISSED'))
);
CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_reported_user ON reports(reported_user_id);
CREATE INDEX idx_reports_reported_trip ON reports(reported_trip_id);

-- ============================================================
-- AUDIT_LOG : journal des actions sensibles (annulations, remboursements, actions admin)
-- ============================================================
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Nullable : certaines ecritures sont declenchees par le systeme (scheduler) sans acteur humain.
    -- Pas de ON DELETE CASCADE : un audit doit survivre a la suppression du compte de son auteur.
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id   UUID,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
