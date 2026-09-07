-- V20 : Web Push (abonnements par navigateur) et pieces d identite televersees.
--
-- push_subscriptions : un abonnement Web Push par navigateur (endpoint unique, cles p256dh/auth
-- du navigateur). Plusieurs par utilisateur (3 au plus, PushSubscriptionService), supprimes
-- avec le compte. La colonne users.push_subscription (V2, jamais exploitee) reste en place et
-- n est plus lue : elle sera retiree par une migration ulterieure une fois le deploiement stable.
CREATE TABLE push_subscriptions (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint      TEXT NOT NULL UNIQUE,
    p256dh        TEXT NOT NULL,
    auth          TEXT NOT NULL,
    user_agent    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    failures      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions(user_id);

-- identity_documents : metadonnees des pieces d identite televersees (recto, verso, selfie).
-- Le fichier lui-meme est chiffre (AES-256-GCM) sur le disque du serveur sous storage_key
-- (IdentityDocumentStorage, ekuiseo.storage.identity-dir) ; jamais le nom d origine. Une
-- face par dossier (remplacement = suppression de l ancien fichier). Supprimes avec le
-- dossier (anonymisation) et 30 jours apres la decision (RetentionScheduler).
CREATE TABLE identity_documents (
    id               UUID PRIMARY KEY,
    verification_id  UUID NOT NULL REFERENCES identity_verifications(id) ON DELETE CASCADE,
    side             VARCHAR(10) NOT NULL,
    content_type     VARCHAR(50) NOT NULL,
    size_bytes       INT NOT NULL,
    storage_key      VARCHAR(100) NOT NULL,
    sha256           CHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_identity_documents_side CHECK (side IN ('FRONT', 'BACK', 'SELFIE')),
    CONSTRAINT uq_identity_documents_verification_side UNIQUE (verification_id, side)
);
