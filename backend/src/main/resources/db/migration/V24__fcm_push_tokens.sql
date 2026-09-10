-- ============================================================
-- V24 : notifications push natives (Firebase Cloud Messaging)
-- ============================================================
-- L application Android/iOS (Capacitor) ne dispose pas de Web Push : elle s enregistre
-- avec un jeton FCM. Les deux formes vivent dans push_subscriptions :
--   - WEBPUSH : endpoint du service push du navigateur + cles p256dh/auth (V20) ;
--   - FCM     : le jeton FCM tient lieu d endpoint, sans cle de chiffrement.
-- L unicite de `endpoint` couvre les deux (un jeton FCM est unique par appareil).
-- ============================================================

ALTER TABLE push_subscriptions
    ADD COLUMN IF NOT EXISTS kind VARCHAR(10) NOT NULL DEFAULT 'WEBPUSH';

ALTER TABLE push_subscriptions DROP CONSTRAINT IF EXISTS chk_push_subscriptions_kind;
ALTER TABLE push_subscriptions ADD CONSTRAINT chk_push_subscriptions_kind
    CHECK (kind IN ('WEBPUSH', 'FCM'));

ALTER TABLE push_subscriptions ALTER COLUMN p256dh DROP NOT NULL;
ALTER TABLE push_subscriptions ALTER COLUMN auth DROP NOT NULL;

-- Un abonnement Web Push garde ses deux cles ; un jeton FCM n en a pas.
ALTER TABLE push_subscriptions DROP CONSTRAINT IF EXISTS chk_push_subscriptions_keys;
ALTER TABLE push_subscriptions ADD CONSTRAINT chk_push_subscriptions_keys
    CHECK (kind <> 'WEBPUSH' OR (p256dh IS NOT NULL AND auth IS NOT NULL));

COMMENT ON COLUMN push_subscriptions.kind IS 'WEBPUSH (navigateur, V20) ou FCM (application native, V24).';
