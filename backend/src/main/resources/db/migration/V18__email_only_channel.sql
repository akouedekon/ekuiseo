-- V18 : l e-mail est le seul canal sortant (decision du fondateur, 2026-09-07 : aucun
-- fournisseur SMS). Les notifications par e-mail deviennent actives par defaut (opt-out) ;
-- les notifications critiques partent de toute facon a l adresse verifiee.
ALTER TABLE user_preferences ALTER COLUMN notify_by_email SET DEFAULT TRUE;
UPDATE user_preferences SET notify_by_email = TRUE;
