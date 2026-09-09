-- ============================================================
-- Ekuiseo — complement de jeu de donnees pour les tests de bout en bout
-- ============================================================
-- Applique APRES docs/donnees-demo.sql (voir e2e/scripts/seed.sh). Idempotent et
-- rejouable : chaque execution remet le trajet E2E a J+3 09:00 (heure du Benin) avec
-- ses 4 places, apres avoir clos les reservations qu'un passage precedent aurait pu
-- laisser ouvertes (une annulation qui aurait echoue, par exemple).
--
-- Le conducteur est Marcellin Sagbo (seed, identite verifiee) : le mode « tout en
-- especes a bord » n'est propose qu'avec un conducteur verifie (BookingService,
-- CASH_REQUIRES_VERIFIED_DRIVER). Les coordonnees d'origine sont celles de la ville
-- « Cotonou » du referentiel geo_places (V3) pour que la recherche depuis l'accueil
-- (ST_DWithin) trouve le trajet a coup sur.
-- ============================================================

-- Reservations residuelles d'un passage precedent : liberees.
UPDATE bookings
   SET status = 'CANCELLED_BY_PASSENGER'
 WHERE trip_id = 'e2e00000-0000-0000-0000-000000000001'
   AND status IN ('PENDING_PAYMENT', 'CONFIRMED');

INSERT INTO trips (
  id, driver_id, vehicle_id, trip_type,
  origin_label, origin_lat, origin_lng, dest_label, dest_lat, dest_lng,
  departure_at, seats_total, seats_available, price_per_seat, instant_booking,
  luggage_policy, description, status
)
VALUES (
  'e2e00000-0000-0000-0000-000000000001',
  'a0000000-0000-0000-0000-000000000006',
  'b0000000-0000-0000-0000-000000000006',
  'INTERURBAIN',
  'Cotonou, gare Jonquet', 6.3703, 2.3912,
  'Bohicon, gare routiere', 7.1786, 2.0667,
  -- J+3 a 09:00, exprime en heure du Benin puis converti en instant.
  ((((now() AT TIME ZONE 'Africa/Porto-Novo')::date + INTERVAL '3 days') + TIME '09:00') AT TIME ZONE 'Africa/Porto-Novo'),
  4, 4, 4000, TRUE,
  'Un sac par passager',
  'Trajet reserve aux tests de bout en bout (Playwright).',
  'PUBLISHED'
)
ON CONFLICT (id) DO UPDATE
   SET departure_at    = EXCLUDED.departure_at,
       seats_total     = EXCLUDED.seats_total,
       seats_available = EXCLUDED.seats_available,
       price_per_seat  = EXCLUDED.price_per_seat,
       status          = EXCLUDED.status;

-- Compte de demonstration du back-office (docs/donnees-demo.sql, +229 01 90 00 00 00) :
-- promu ADMIN pour le parcours « back-office » (tests/back-office.spec.ts). En production,
-- la promotion reste manuelle (docs/DEPLOIEMENT.md, section 9).
UPDATE users
   SET role = 'ADMIN', status = 'ACTIVE'
 WHERE id = 'a0000000-0000-0000-0000-000000000031';
