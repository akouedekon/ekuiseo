-- V11 : lot 1.1 de l audit (authentification et sessions).
--
-- refresh_tokens : chaque jeton de rafraichissement emis est enregistre par son jti.
--   Rotation a chaque /auth/refresh (l ancien est revoque et pointe vers son
--   remplacant), revocation a la deconnexion, a la suspension et a la correction de
--   contact, duree absolue (90 jours) independante du glissement, et detection de
--   reutilisation : un jeton deja revoque presente a nouveau revoque toute sa famille.
-- users.pending_email : adresse en attente de confirmation par code (changement d e-mail).
-- uq_users_email_lower : l e-mail est le canal des codes de connexion, il doit etre unique
--   (verifie sans doublon en production le 2026-09-05 avant cette migration).
-- users.status accepte desormais PENDING_VERIFICATION (compte cree a l inscription,
--   active a la verification du premier code, purge apres 24 h sinon).
CREATE TABLE refresh_tokens (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id           UUID NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    replaced_by         UUID
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);

ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_otp_codes_phone_purpose ON otp_codes(phone, purpose);
