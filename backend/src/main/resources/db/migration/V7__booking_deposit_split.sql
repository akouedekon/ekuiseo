-- Ekuiseo - V7 : paiement fractionne (acompte en ligne + solde en especes a bord),
-- regle metier n.21. Decompose bookings.amount, fige a la reservation :
--   amount                = total du au passager (inchange)
--   service_fee            = commission plateforme (inchange)
--   deposit_amount          = preleve en ligne maintenant (Kkiapay)
--   balance_due_on_board    = amount - deposit_amount, regle en especes au conducteur pendant le trajet
--
-- Le mode de paiement (bookings.payment_method) passe de deux valeurs (MOMO/CASH)
-- a trois : MOMO_DEPOSIT (nouveau defaut), MOMO_FULL (ancien comportement MOMO,
-- conserve tel quel pour les reservations deja existantes, voir migration de
-- donnees ci-dessous), CASH (inchange).

ALTER TABLE bookings
    ADD COLUMN deposit_amount       BIGINT,
    ADD COLUMN balance_due_on_board BIGINT;

-- Migration de donnees pour les reservations deja existantes (schema neuf en
-- pratique, mais on ne suppose jamais une base vide) : une reservation MOMO
-- historique a toujours ete payee integralement en ligne -> devient MOMO_FULL,
-- avec deposit_amount = amount (rien ne change pour son reversement eventuel,
-- deja calcule ou a calculer sur la totalite). Une reservation CASH n'a jamais
-- rien fait transiter par la plateforme -> deposit_amount = 0.
UPDATE bookings SET deposit_amount = amount, balance_due_on_board = 0 WHERE payment_method = 'MOMO';
UPDATE bookings SET deposit_amount = 0, balance_due_on_board = amount WHERE payment_method = 'CASH';
UPDATE bookings SET payment_method = 'MOMO_FULL' WHERE payment_method = 'MOMO';

ALTER TABLE bookings
    ALTER COLUMN deposit_amount SET NOT NULL,
    ALTER COLUMN balance_due_on_board SET NOT NULL,
    ALTER COLUMN payment_method SET DEFAULT 'MOMO_DEPOSIT';

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_deposit_balance CHECK (deposit_amount + balance_due_on_board = amount),
    ADD CONSTRAINT chk_bookings_deposit_range CHECK (deposit_amount >= 0 AND deposit_amount <= amount);
