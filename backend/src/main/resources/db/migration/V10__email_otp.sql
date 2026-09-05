-- V10 : code de verification livre par e-mail.
-- users.email_verified : l'adresse a recu et validé un code (miroir de phone_verified).
-- otp_codes.channel   : canal utilise pour ce code (EMAIL ou SMS), pour savoir quel
--                       drapeau poser a la verification.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE otp_codes ADD COLUMN IF NOT EXISTS channel VARCHAR(10) NOT NULL DEFAULT 'SMS';
