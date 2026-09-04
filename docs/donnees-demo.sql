-- ============================================================
-- Ekuiseo — jeu de donnees de demonstration (Benin)
-- ============================================================
-- A charger avec : ./scripts/seed-demo.sh (voir ce script pour les details et
-- l'option --reset). Peut aussi etre charge directement :
--   docker compose exec -T postgis psql -U ekuiseo -d ekuiseo < docs/donnees-demo.sql
--
-- Contenu : 30 utilisateurs beninois (+ 1 compte administrateur), 9 vehicules, 20
-- trajets (interurbain et quotidien recurrent), des reservations dans les 6 etats
-- possibles, des paiements Kkiapay, des avis, quelques conversations/messages, des
-- demandes de verification d'identite (dont 2 en attente), des signalements a
-- moderer (les 4 statuts), et 2 lots de reversement conducteur (1 verse, 1 en
-- attente) — de quoi peupler les ecrans du back-office (migrations V2/V5/V6).
--
-- Conventions :
--   - Mot de passe de TOUS les comptes de demonstration : Demo1234!
--     (hash bcrypt precalcule ci-dessous, verifiable par BCryptPasswordEncoder)
--   - Identifiants UUID lisibles par prefixe : a...=users, b...=vehicles,
--     c...=trips, d...=bookings, e...=payments, f...=reviews,
--     11...=conversations, 12...=messages.
--   - Les dates de trajet sont relatives a l'instant de chargement (now() +/-
--     interval) : le jeu de donnees reste toujours "d'actualite", quelle que
--     soit la date a laquelle il est charge.
--   - Idempotent : ON CONFLICT (id) DO NOTHING sur chaque table, un rechargement
--     sans "--reset" ne duplique rien (mais ne met pas a jour non plus les
--     lignes deja presentes).
--
-- Schema source de verite : backend/src/main/resources/db/migration/V1__init.sql
-- Enumerations source de verite : backend/src/main/java/bj/ekuiseo/api/domain/enums/
-- ============================================================

BEGIN;

-- Hash bcrypt (10 rounds) du mot de passe "Demo1234!" pour tous les comptes.
-- \set n'est pas utilisable en dehors de psql interactif de maniere fiable via
-- exec -T ; le hash est donc repete litteralement dans chaque INSERT users.

-- ============================================================
-- 1. UTILISATEURS (30) — 9 conducteurs (avec vehicule), 21 passagers
-- ============================================================
INSERT INTO users (id, phone, email, first_name, last_name, password_hash, bio, birth_date, phone_verified, identity_verified, status)
VALUES
  -- --- Conducteurs -----------------------------------------------------
  ('a0000000-0000-0000-0000-000000000001', '+22997001001', 'sylvestre.zannou@example.bj', 'Sylvestre', 'Zannou',   '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Conducteur regulier Cotonou-Bohicon depuis 3 ans, vehicule climatise.', '1985-03-12', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000002', '+22997001002', 'moucharafou.gomina@example.bj', 'Moucharafou', 'Gomina', '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Minibus Cotonou-Parakou, 3 rotations par semaine.', '1979-11-02', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000003', '+22997001003', 'boni.kora@example.bj', 'Boni', 'Kora',                  '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Trajet Cotonou-Natitingou, depart tot le matin.', '1982-06-20', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000004', '+22997001004', 'rene.vodounnon@example.bj', 'Rene', 'Vodounnon',        '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Navette quotidienne Cotonou-Porto-Novo.', '1990-01-15', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000005', '+22997001005', 'seraphin.kpossou@example.bj', 'Seraphin', 'Kpossou',   '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Liaison transfrontaliere Cotonou-Lome, papiers en regle.', '1984-09-08', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000006', '+22997001006', 'marcellin.sagbo@example.bj', 'Marcellin', 'Sagbo',      '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Base a Bohicon, trajets vers Cotonou tous les jours de marche.', '1988-04-27', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000007', '+22997001007', 'gildas.codjo@example.bj', 'Gildas', 'Codjo',           '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Nouveau conducteur, trajet Cotonou-Parakou en preparation.', '1993-12-30', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000008', '+22997001008', 'wilfried.tossou@example.bj', 'Wilfried', 'Tossou',      '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Navette domicile-travail Abomey-Calavi-Cotonou, depart 7h.', '1991-07-19', TRUE, TRUE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000009', '+22997001009', 'judicael.gbedjinou@example.bj', 'Judicael', 'Gbedjinou', '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Navette Abomey-Calavi-Cotonou, depart 6h30, place pour 4.', '1987-02-11', TRUE, TRUE, 'ACTIVE'),

  -- --- Passagers ---------------------------------------------------------
  ('a0000000-0000-0000-0000-000000000010', '+22996002010', NULL, 'Koffi', 'Dossou-Yovo',        '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1995-05-14', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000011', '+22996002011', 'esperance.ahouansou@example.bj', 'Esperance', 'Ahouansou', '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1998-08-03', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000012', '+22996002012', NULL, 'Adjoavi', 'Houngbedji',        '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1996-10-21', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000013', '+22996002013', NULL, 'Bertin', 'Agbodjinou',         '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1992-02-09', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000014', '+22996002014', 'rachidatou.idrissou@example.bj', 'Rachidatou', 'Idrissou', '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Voyage regulierement en famille entre Cotonou et Parakou.', '1989-12-01', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000015', '+22996002015', NULL, 'Fofana', 'Sero',               '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1994-03-17', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000016', '+22996002016', NULL, 'Innocent', 'Hounkpatin',       '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1997-06-25', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000017', '+22996002017', NULL, 'Chimene', 'Dossa',             '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1999-01-30', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000018', '+22996002018', 'nadege.sossou@example.bj', 'Nadege', 'Sossou',            '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Employee de bureau, trajet quotidien Calavi-Cotonou.', '1993-09-09', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000019', '+22996002019', NULL, 'Parfait', 'Akakpo',            '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1991-11-11', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000020', '+22996002020', NULL, 'Bijou', 'Adande',              '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1996-04-04', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000021', '+22996002021', NULL, 'Adeyemi', 'Babatunde',         '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1990-07-07', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000022', '+22996002022', NULL, 'Fasilat', 'Olabisi',           '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1995-02-18', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000023', '+22996002023', NULL, 'Yerima', 'Wabi',               '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1988-10-10', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000024', '+22996002024', NULL, 'Prudence', 'Aina',             '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Nouvelle inscrite, pas encore de reservation.', '2000-05-05', FALSE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000025', '+22996002025', 'solange.hounsou@example.bj', 'Solange', 'Hounsou',        '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Trajet quotidien Calavi-Cotonou avec sa collegue.', '1994-08-22', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000026', '+22996002026', NULL, 'Theophile', 'Agossou',         '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1986-03-03', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000027', '+22996002027', NULL, 'Christelle', 'Zinsou',         '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1997-12-12', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000028', '+22996002028', NULL, 'Felicite', 'Dovonou',          '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1992-09-27', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000029', '+22996002029', NULL, 'Yacine', 'Abdoulaye',          '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Commercante, voyage vers Lome pour ses achats.', '1983-01-01', TRUE, FALSE, 'ACTIVE'),
  ('a0000000-0000-0000-0000-000000000030', '+22996002030', NULL, 'Larissa', 'Amoussou',          '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', NULL, '1999-11-23', TRUE, FALSE, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 2. VEHICULES (un par conducteur actif)
-- ============================================================
INSERT INTO vehicles (id, owner_id, brand, model, color, plate, seats, comfort_level, verified)
VALUES
  ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Toyota',  'Corolla', 'Gris',    'AB 1234 RB', 4, 'COMFORT', TRUE),
  ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'Toyota',  'Hiace',   'Blanc',   'AC 5678 RB', 7, 'BASIC',   TRUE),
  ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003', 'Toyota',  'Avensis', 'Noir',    'AD 2468 RB', 6, 'COMFORT', TRUE),
  ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004', 'Hyundai', 'Accent',  'Bleu',    'AE 1357 RB', 4, 'BASIC',   TRUE),
  ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', 'Toyota',  'Camry',   'Argent',  'AF 9876 RB', 4, 'PREMIUM', TRUE),
  ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000006', 'Renault', 'Logan',   'Blanc',   'AG 3216 RB', 4, 'BASIC',   TRUE),
  ('b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000007', 'Nissan',  'Almera',  'Gris',    'AH 6543 RB', 4, 'BASIC',   FALSE),
  ('b0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000008', 'Kia',     'Picanto', 'Rouge',   'AI 1122 RB', 4, 'BASIC',   TRUE),
  ('b0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000009', 'Toyota',  'Yaris',   'Blanc',   'AJ 3344 RB', 4, 'COMFORT', TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 3. TRAJETS
-- ============================================================
-- 3.a Interurbain -------------------------------------------------------
INSERT INTO trips (id, driver_id, vehicle_id, trip_type, origin_label, origin_lat, origin_lng, dest_label, dest_lat, dest_lng, departure_at, seats_total, seats_available, price_per_seat, instant_booking, luggage_policy, description, status)
VALUES
  -- Cotonou <-> Bohicon (Sylvestre)
  ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'INTERURBAIN', 'Cotonou, gare Jonquet', 6.3703, 2.3912, 'Bohicon, gare routiere', 7.1786, 2.0667, now() + interval '2 days' + interval '8 hours', 4, 1, 4000, TRUE, 'Un sac par passager', 'Depart ponctuel, climatisation.', 'PUBLISHED'),
  ('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'INTERURBAIN', 'Bohicon, gare routiere', 7.1786, 2.0667, 'Cotonou, gare Jonquet', 6.3703, 2.3912, now() - interval '5 days' + interval '9 hours', 4, 2, 4500, TRUE, 'Un sac par passager', 'Retour Bohicon-Cotonou.', 'COMPLETED'),

  -- Cotonou <-> Parakou (Moucharafou, minibus)
  ('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', 'INTERURBAIN', 'Cotonou, Etoile Rouge', 6.3703, 2.3912, 'Parakou, gare routiere', 9.3372, 2.6303, now() + interval '4 days' + interval '6 hours', 7, 3, 10000, FALSE, '1 bagage en soute inclus', 'Minibus 7 places, arret a Bohicon et Save.', 'PUBLISHED'),
  ('c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', 'INTERURBAIN', 'Parakou, gare routiere', 9.3372, 2.6303, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() - interval '10 days' + interval '6 hours', 7, 2, 9500, FALSE, '1 bagage en soute inclus', 'Retour Parakou-Cotonou.', 'COMPLETED'),

  -- Cotonou <-> Natitingou (Boni)
  ('c0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000003', 'INTERURBAIN', 'Cotonou, Vedoko', 6.3703, 2.3912, 'Natitingou, centre-ville', 10.3042, 1.3796, now() + interval '6 days' + interval '5 hours', 6, 4, 14000, TRUE, '2 bagages max', 'Longue distance, pause a Djougou.', 'PUBLISHED'),
  ('c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000003', 'INTERURBAIN', 'Natitingou, centre-ville', 10.3042, 1.3796, 'Cotonou, Vedoko', 6.3703, 2.3912, now() - interval '20 days' + interval '5 hours', 6, 3, 13500, TRUE, '2 bagages max', 'Retour Natitingou-Cotonou.', 'COMPLETED'),

  -- Cotonou <-> Porto-Novo (Rene)
  ('c0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000004', 'INTERURBAIN', 'Cotonou, Dantokpa', 6.3703, 2.3912, 'Porto-Novo, Ouando', 6.4969, 2.6289, now() + interval '1 days' + interval '17 hours', 4, 2, 1200, TRUE, NULL, 'Trajet court, plusieurs rotations par jour.', 'PUBLISHED'),
  ('c0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000004', 'INTERURBAIN', 'Porto-Novo, Ouando', 6.4969, 2.6289, 'Cotonou, Dantokpa', 6.3703, 2.3912, now() - interval '3 days' + interval '17 hours', 4, 2, 1500, TRUE, NULL, 'Retour Porto-Novo-Cotonou.', 'COMPLETED'),

  -- Cotonou <-> Lome (Seraphin, transfrontalier)
  ('c0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000005', 'INTERURBAIN', 'Cotonou, Godomey', 6.3703, 2.3912, 'Lome, Grand Marche', 6.1319, 1.2228, now() + interval '5 days' + interval '7 hours', 4, 3, 4000, FALSE, 'Piece d''identite obligatoire (frontiere)', 'Passage frontiere Hillacondji, papiers a jour requis.', 'PUBLISHED'),
  ('c0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000005', 'INTERURBAIN', 'Lome, Grand Marche', 6.1319, 1.2228, 'Cotonou, Godomey', 6.3703, 2.3912, now() + interval '8 days' + interval '7 hours', 4, 4, 4000, FALSE, 'Piece d''identite obligatoire (frontiere)', 'Trajet annule par le conducteur (probleme de vehicule).', 'CANCELLED'),

  -- Cotonou -> Bohicon (Marcellin, complet)
  ('c0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000006', 'b0000000-0000-0000-0000-000000000006', 'INTERURBAIN', 'Cotonou, gare Jonquet', 6.3703, 2.3912, 'Bohicon, gare routiere', 7.1786, 2.0667, now() + interval '3 days' + interval '15 hours', 4, 0, 4500, TRUE, 'Un sac par passager', 'Toutes les places sont deja reservees.', 'FULL'),

  -- Cotonou -> Parakou (Gildas, brouillon non publie)
  ('c0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000007', 'b0000000-0000-0000-0000-000000000007', 'INTERURBAIN', 'Cotonou, Etoile Rouge', 6.3703, 2.3912, 'Parakou, gare routiere', 9.3372, 2.6303, now() + interval '10 days' + interval '6 hours', 4, 4, 11000, TRUE, NULL, 'En cours de preparation, pas encore publie.', 'DRAFT')
ON CONFLICT (id) DO NOTHING;

-- 3.b Quotidien recurrent : Abomey-Calavi -> Cotonou -------------------
-- Deux conducteurs, deux "trajets parents" porteurs de recurrence_rule, chacun
-- avec quelques occurrences (parent_trip_id) passees et a venir, comme le
-- ferait RecurrenceService (voir README, section "Regles metier").
INSERT INTO trips (id, driver_id, vehicle_id, trip_type, origin_label, origin_lat, origin_lng, dest_label, dest_lat, dest_lng, departure_at, seats_total, seats_available, price_per_seat, instant_booking, description, status, recurrence_rule, parent_trip_id)
VALUES
  -- Parent A : Wilfried, depart 7h
  ('c0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000008', 'b0000000-0000-0000-0000-000000000008', 'QUOTIDIEN', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() + interval '1 days' + interval '7 hours', 4, 3, 800, TRUE, 'Trajet domicile-travail, tous les jours ouvres a 7h.', 'PUBLISHED', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', NULL),
  ('c0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000008', 'b0000000-0000-0000-0000-000000000008', 'QUOTIDIEN', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() - interval '1 days' + interval '7 hours', 4, 3, 800, TRUE, 'Occurrence passee.', 'COMPLETED', NULL, 'c0000000-0000-0000-0000-000000000013'),
  ('c0000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000008', 'b0000000-0000-0000-0000-000000000008', 'QUOTIDIEN', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() - interval '2 days' + interval '7 hours', 4, 3, 800, TRUE, 'Occurrence passee.', 'COMPLETED', NULL, 'c0000000-0000-0000-0000-000000000013'),
  ('c0000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000008', 'b0000000-0000-0000-0000-000000000008', 'QUOTIDIEN', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() + interval '2 days' + interval '7 hours', 4, 3, 800, TRUE, 'Occurrence a venir.', 'PUBLISHED', NULL, 'c0000000-0000-0000-0000-000000000013'),

  -- Parent B : Judicael, depart 6h30
  ('c0000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000009', 'b0000000-0000-0000-0000-000000000009', 'QUOTIDIEN', 'Abomey-Calavi, campus UAC', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() + interval '1 days' + interval '6 hours 30 minutes', 4, 2, 800, TRUE, 'Trajet domicile-travail, tous les jours ouvres a 6h30.', 'PUBLISHED', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', NULL),
  ('c0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000009', 'b0000000-0000-0000-0000-000000000009', 'QUOTIDIEN', 'Abomey-Calavi, campus UAC', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() - interval '1 days' + interval '6 hours 30 minutes', 4, 2, 800, TRUE, 'Occurrence passee.', 'COMPLETED', NULL, 'c0000000-0000-0000-0000-000000000017'),
  ('c0000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000009', 'b0000000-0000-0000-0000-000000000009', 'QUOTIDIEN', 'Abomey-Calavi, campus UAC', 6.4489, 2.3556, 'Cotonou, Etoile Rouge', 6.3703, 2.3912, now() - interval '3 days' + interval '6 hours 30 minutes', 4, 3, 800, TRUE, 'Occurrence passee, un passager absent.', 'COMPLETED', NULL, 'c0000000-0000-0000-0000-000000000017')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 4. RESERVATIONS (6 etats possibles, tous representes)
-- ============================================================
-- Paiement fractionne (migration V7, regle metier n.21) : deposit_amount est ce
-- qui est reellement preleve en ligne maintenant (Kkiapay), balance_due_on_board
-- le solde regle en especes au conducteur pendant le trajet. En MOMO_DEPOSIT
-- (mode par defaut), deposit_amount = min(amount, roundUp5(max(1000, service_fee)))
-- — voir FeePolicy#computeDepositAmount, meme formule reprise ici a la main. La
-- plupart des reservations MoMo sont en MOMO_DEPOSIT ; 3 sont volontairement en
-- MOMO_FULL (passager qui prefere tout regler en ligne) pour que le back-office
-- montre les deux cas. CASH : deposit_amount=0, balance_due_on_board=amount
-- (rien ne transite par la plateforme).
INSERT INTO bookings (id, trip_id, passenger_id, seats, amount, service_fee, status, payment_method, deposit_amount, balance_due_on_board)
VALUES
  -- Trajet 1 : Cotonou->Bohicon (PUBLISHED)
  ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000011', 2, 8000, 640, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 7000),
  ('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000012', 1, 4000, 320, 'PENDING_PAYMENT', 'MOMO_DEPOSIT', 1000, 3000),

  -- Trajet 2 : Bohicon->Cotonou (COMPLETED)
  ('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000010', 1, 4500, 360, 'COMPLETED', 'MOMO_DEPOSIT', 1000, 3500),
  ('d0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000013', 1, 4500, 360, 'COMPLETED', 'MOMO_DEPOSIT', 1000, 3500),

  -- Trajet 3 : Cotonou->Parakou (PUBLISHED) — Rachidatou (famille) paie tout en ligne (MOMO_FULL)
  ('d0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000014', 3, 30000, 2400, 'CONFIRMED', 'MOMO_FULL', 30000, 0),
  ('d0000000-0000-0000-0000-000000000006', 'c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000015', 1, 10000, 800, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 9000),
  ('d0000000-0000-0000-0000-000000000007', 'c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000016', 1, 10000, 800, 'CANCELLED_BY_DRIVER', 'MOMO_DEPOSIT', 1000, 9000),

  -- Trajet 4 : Parakou->Cotonou (COMPLETED) — meme famille, meme choix MOMO_FULL au retour
  ('d0000000-0000-0000-0000-000000000008', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000014', 3, 28500, 2280, 'COMPLETED', 'MOMO_DEPOSIT', 2280, 26220),
  ('d0000000-0000-0000-0000-000000000009', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000017', 1, 9500, 760, 'COMPLETED', 'MOMO_DEPOSIT', 1000, 8500),
  ('d0000000-0000-0000-0000-000000000010', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000023', 1, 9500, 760, 'NO_SHOW', 'MOMO_DEPOSIT', 1000, 8500),

  -- Trajet 5 : Cotonou->Natitingou (PUBLISHED)
  ('d0000000-0000-0000-0000-000000000011', 'c0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000019', 2, 28000, 2240, 'CONFIRMED', 'MOMO_DEPOSIT', 2240, 25760),

  -- Trajet 6 : Natitingou->Cotonou (COMPLETED) — longue distance, Bijou prefere tout regler en ligne (MOMO_FULL)
  ('d0000000-0000-0000-0000-000000000012', 'c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000020', 3, 40500, 3240, 'COMPLETED', 'MOMO_FULL', 40500, 0),

  -- Trajet 7 : Cotonou->Porto-Novo (PUBLISHED)
  ('d0000000-0000-0000-0000-000000000013', 'c0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000021', 1, 1200, 100, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 200),
  ('d0000000-0000-0000-0000-000000000014', 'c0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000022', 1, 1200, 100, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 200),

  -- Trajet 8 : Porto-Novo->Cotonou (COMPLETED)
  ('d0000000-0000-0000-0000-000000000015', 'c0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000021', 2, 3000, 240, 'COMPLETED', 'MOMO_DEPOSIT', 1000, 2000),

  -- Trajet 9 : Cotonou->Lome (PUBLISHED) — Yacine prefere tout regler en ligne avant la frontiere (MOMO_FULL)
  ('d0000000-0000-0000-0000-000000000016', 'c0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000029', 1, 4000, 320, 'CONFIRMED', 'MOMO_FULL', 4000, 0),
  ('d0000000-0000-0000-0000-000000000017', 'c0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000030', 1, 4000, 320, 'CANCELLED_BY_PASSENGER', 'MOMO_DEPOSIT', 1000, 3000),

  -- Trajet 11 : Cotonou->Bohicon complet (FULL)
  ('d0000000-0000-0000-0000-000000000018', 'c0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000026', 2, 9000, 720, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 8000),
  ('d0000000-0000-0000-0000-000000000019', 'c0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000027', 2, 9000, 720, 'CONFIRMED', 'MOMO_DEPOSIT', 1000, 8000),

  -- Quotidien parent A + occurrences (Nadege, paiement especes a bord)
  ('d0000000-0000-0000-0000-000000000020', 'c0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000018', 1, 800, 65, 'CONFIRMED', 'CASH', 0, 800),
  ('d0000000-0000-0000-0000-000000000021', 'c0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000018', 1, 800, 65, 'COMPLETED', 'CASH', 0, 800),
  ('d0000000-0000-0000-0000-000000000022', 'c0000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000018', 1, 800, 65, 'COMPLETED', 'CASH', 0, 800),
  ('d0000000-0000-0000-0000-000000000023', 'c0000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000018', 1, 800, 65, 'CONFIRMED', 'CASH', 0, 800),

  -- Quotidien parent B + occurrences (Solange en especes, Felicite en MoMo)
  ('d0000000-0000-0000-0000-000000000024', 'c0000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000025', 2, 1600, 130, 'CONFIRMED', 'CASH', 0, 1600),
  ('d0000000-0000-0000-0000-000000000025', 'c0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000025', 2, 1600, 130, 'COMPLETED', 'CASH', 0, 1600),
  -- deposit plafonne a amount (800 < plancher 1000 FCFA) : equivaut numeriquement
  -- a MOMO_FULL, mais reste MOMO_DEPOSIT (c'est bien ce mode que Felicite a choisi).
  ('d0000000-0000-0000-0000-000000000026', 'c0000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000028', 1, 800, 65, 'NO_SHOW', 'MOMO_DEPOSIT', 800, 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 5. PAIEMENTS (Kkiapay) — un par reservation reglee en MoMo (deposit ou total,
--    migration V7). payments.amount reflete ce qui est REELLEMENT preleve en
--    ligne, donc bookings.deposit_amount (et non plus bookings.amount) pour les
--    reservations MOMO_DEPOSIT ; pour les 3 MOMO_FULL (d005, d012, d016 —
--    deposit_amount = amount), la valeur ne change pas. fee reste le frais de
--    traitement Kkiapay (~1,5% du montant preleve, purement informatif, non
--    contraint en base).
-- ============================================================
INSERT INTO payments (id, booking_id, provider, provider_tx_id, amount, fee, channel, status)
VALUES
  ('e0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001', 'KKIAPAY', 'KKIAPAY-DEMO-0001', 1000,  15,  'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000002', 'KKIAPAY', 'KKIAPAY-DEMO-0002', 1000,  15,  'MOOV',   'INITIATED'),
  ('e0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000003', 'KKIAPAY', 'KKIAPAY-DEMO-0003', 1000,  15,  'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000004', 'd0000000-0000-0000-0000-000000000004', 'KKIAPAY', 'KKIAPAY-DEMO-0004', 1000,  15,  'CELTIIS','SUCCEEDED'),
  -- d005 : MOMO_FULL, deposit_amount = amount -> paiement inchange
  ('e0000000-0000-0000-0000-000000000005', 'd0000000-0000-0000-0000-000000000005', 'KKIAPAY', 'KKIAPAY-DEMO-0005', 30000, 450, 'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000006', 'd0000000-0000-0000-0000-000000000006', 'KKIAPAY', 'KKIAPAY-DEMO-0006', 1000,  15,  'MOOV',   'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000007', 'd0000000-0000-0000-0000-000000000007', 'KKIAPAY', 'KKIAPAY-DEMO-0007', 1000,  15,  'MTN',    'REFUNDED'),
  ('e0000000-0000-0000-0000-000000000008', 'd0000000-0000-0000-0000-000000000008', 'KKIAPAY', 'KKIAPAY-DEMO-0008', 2280,  34,  'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000009', 'd0000000-0000-0000-0000-000000000009', 'KKIAPAY', 'KKIAPAY-DEMO-0009', 1000,  15,  'CELTIIS','SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000010', 'd0000000-0000-0000-0000-000000000010', 'KKIAPAY', 'KKIAPAY-DEMO-0010', 1000,  15,  'MOOV',   'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000011', 'd0000000-0000-0000-0000-000000000011', 'KKIAPAY', 'KKIAPAY-DEMO-0011', 2240,  34,  'MTN',    'SUCCEEDED'),
  -- d012 : MOMO_FULL, deposit_amount = amount -> paiement inchange
  ('e0000000-0000-0000-0000-000000000012', 'd0000000-0000-0000-0000-000000000012', 'KKIAPAY', 'KKIAPAY-DEMO-0012', 40500, 607, 'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000013', 'd0000000-0000-0000-0000-000000000013', 'KKIAPAY', 'KKIAPAY-DEMO-0013', 1000,  15,  'MOOV',   'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000014', 'd0000000-0000-0000-0000-000000000014', 'KKIAPAY', 'KKIAPAY-DEMO-0014', 1000,  15,  'MTN',    'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000015', 'd0000000-0000-0000-0000-000000000015', 'KKIAPAY', 'KKIAPAY-DEMO-0015', 1000,  15,  'MTN',    'SUCCEEDED'),
  -- d016 : MOMO_FULL, deposit_amount = amount -> paiement inchange
  ('e0000000-0000-0000-0000-000000000016', 'd0000000-0000-0000-0000-000000000016', 'KKIAPAY', 'KKIAPAY-DEMO-0016', 4000,  60,  'CARD',   'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000017', 'd0000000-0000-0000-0000-000000000017', 'KKIAPAY', 'KKIAPAY-DEMO-0017', 1000,  15,  'MTN',    'REFUNDED'),
  ('e0000000-0000-0000-0000-000000000018', 'd0000000-0000-0000-0000-000000000018', 'KKIAPAY', 'KKIAPAY-DEMO-0018', 1000,  15,  'MOOV',   'SUCCEEDED'),
  ('e0000000-0000-0000-0000-000000000019', 'd0000000-0000-0000-0000-000000000019', 'KKIAPAY', 'KKIAPAY-DEMO-0019', 1000,  15,  'MTN',    'SUCCEEDED'),
  -- d026 : deposit plafonne a amount (800 < plancher 1000) -> paiement inchange
  ('e0000000-0000-0000-0000-000000000020', 'd0000000-0000-0000-0000-000000000026', 'KKIAPAY', 'KKIAPAY-DEMO-0020', 800,   12,  'MTN',    'SUCCEEDED')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 6. AVIS (uniquement sur des reservations CONFIRMED/COMPLETED, comme l'exige
--    ReviewService)
-- ============================================================
INSERT INTO reviews (id, trip_id, author_id, target_id, role, rating, comment)
VALUES
  -- Trajet 2 (Bohicon->Cotonou, complete)
  ('f0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'PASSENGER', 5, 'Conducteur ponctuel et vehicule tres propre. Je recommande.'),
  ('f0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000010', 'DRIVER',    5, 'Passager courtois, a l''heure au point de rendez-vous.'),
  ('f0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000001', 'PASSENGER', 4, 'Bon trajet, un peu de retard au depart de Bohicon.'),

  -- Trajet 4 (Parakou->Cotonou, complete)
  ('f0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000002', 'PASSENGER', 5, 'Minibus confortable pour un si long trajet, merci.'),
  ('f0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000014', 'DRIVER',    5, 'Famille agreable, bagages bien organises.'),
  ('f0000000-0000-0000-0000-000000000006', 'c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000002', 'PASSENGER', 4, 'Depart un peu tardif mais trajet sans souci.'),

  -- Trajet 6 (Natitingou->Cotonou, complete)
  ('f0000000-0000-0000-0000-000000000007', 'c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000020', 'a0000000-0000-0000-0000-000000000003', 'PASSENGER', 5, 'Tres bon conducteur sur la route du nord, tres rassurant.'),
  ('f0000000-0000-0000-0000-000000000008', 'c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000020', 'DRIVER',    5, 'Groupe sympathique, aucun probleme.'),

  -- Trajet 8 (Porto-Novo->Cotonou, complete)
  ('f0000000-0000-0000-0000-000000000009', 'c0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000021', 'a0000000-0000-0000-0000-000000000004', 'PASSENGER', 4, 'Trajet rapide, RAS.'),

  -- Occurrences quotidiennes (Nadege / Wilfried)
  ('f0000000-0000-0000-0000-000000000010', 'c0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000008', 'PASSENGER', 5, 'Conducteur tres fiable, jamais en retard le matin.'),
  ('f0000000-0000-0000-0000-000000000011', 'c0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000018', 'DRIVER',    5, 'Passagere reguliere, toujours a l''heure.'),

  -- Occurrences quotidiennes (Solange / Judicael)
  ('f0000000-0000-0000-0000-000000000012', 'c0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000025', 'a0000000-0000-0000-0000-000000000009', 'PASSENGER', 5, 'Trajet quotidien agreable, bonne ambiance.'),
  ('f0000000-0000-0000-0000-000000000013', 'c0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000025', 'DRIVER',    5, 'Excellente passagere, tres ponctuelle.')
ON CONFLICT (id) DO NOTHING;

-- Recalcule rating_avg / rating_count pour tous les utilisateurs ayant recu
-- au moins un avis (meme logique que ReviewService#recomputeRating : moyenne
-- simple arrondie a 2 decimales).
UPDATE users u
SET rating_avg = sub.avg_rating,
    rating_count = sub.nb_reviews
FROM (
  SELECT target_id, ROUND(AVG(rating)::numeric, 2) AS avg_rating, COUNT(*) AS nb_reviews
  FROM reviews
  GROUP BY target_id
) sub
WHERE u.id = sub.target_id;

-- ============================================================
-- 7. MESSAGERIE (un exemple de conversation par reservation confirmee)
-- ============================================================
INSERT INTO conversations (id, booking_id)
VALUES
  ('11000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001'),
  ('11000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000005')
ON CONFLICT (id) DO NOTHING;

INSERT INTO messages (id, conversation_id, sender_id, body, read_at)
VALUES
  ('12000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000011', 'Bonjour, je serai a l''arret 10 minutes avant le depart, est-ce correct ?', now() - interval '1 day'),
  ('12000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Bonjour, oui c''est parfait, a tout a l''heure.', NULL),
  ('12000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000014', 'Bonjour, nous sommes 3 avec des bagages, est-ce qu''il y a de la place en soute ?', now() - interval '2 days'),
  ('12000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'Oui, pas de souci, le minibus a un grand coffre.', now() - interval '2 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 8. COMPTE ADMINISTRATEUR (back-office, migration V2 : users.role)
-- ============================================================
INSERT INTO users (id, phone, email, first_name, last_name, password_hash, bio, birth_date, phone_verified, identity_verified, status, role)
VALUES
  ('a0000000-0000-0000-0000-000000000031', '+22990000000', 'admin@ekuiseo.bj', 'Fabrice', 'Houngbedji', '$2b$10$1HiIum//2YsrIHjg3wyLkOwtc.julv.abcHg5HYvmf5x36kDVHppS', 'Compte back-office (moderation, verifications, reversements).', '1988-01-01', TRUE, TRUE, 'ACTIVE', 'ADMIN')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 9. VERIFICATIONS D'IDENTITE (migration V6 : identity_verifications)
-- ============================================================
-- Une ligne par utilisateur qui a deja soumis une piece ; l'absence de ligne
-- (les 26 autres utilisateurs) signifie NOT_SUBMITTED, ce qui est volontaire.
INSERT INTO identity_verifications (id, user_id, document_type, document_number, status, submitted_at, reviewed_at, reviewed_by, rejection_reason)
VALUES
  -- En attente de traitement par le back-office (regle metier n.19)
  ('21000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000007', 'CNI', 'CNI-BJ-2024-004471', 'PENDING', now() - interval '1 days', NULL, NULL, NULL),
  ('21000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000010', 'DRIVER_LICENSE', 'PC-BJ-2023-118820', 'PENDING', now() - interval '3 hours', NULL, NULL, NULL),
  -- Deja traitees (historique, pour montrer les deux issues possibles)
  ('21000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'CNI', 'CNI-BJ-2022-009981', 'APPROVED', now() - interval '60 days', now() - interval '58 days', 'a0000000-0000-0000-0000-000000000031', NULL),
  ('21000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000029', 'PASSPORT', 'BJ0219938', 'REJECTED', now() - interval '10 days', now() - interval '9 days', 'a0000000-0000-0000-0000-000000000031', 'Photo de la piece illisible, merci de resoumettre avec un meilleur eclairage.')
ON CONFLICT (id) DO NOTHING;

-- Coherence avec le drapeau legacy users.identity_verified : seul l'utilisateur
-- APPROVED ci-dessus (Sylvestre, deja TRUE plus haut) doit rester verifie ; les
-- autres (PENDING/REJECTED) restent a FALSE, deja le cas dans la section 1.

-- ============================================================
-- 10. SIGNALEMENTS (migration V2 + V6 : reports, moderation)
-- ============================================================
INSERT INTO reports (id, reporter_id, reported_user_id, reported_trip_id, reason_code, details, status, resolution_note, resolved_by, resolved_at)
VALUES
  -- OPEN : vient d'arriver, personne ne l'a encore pris en charge
  ('20000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000015', NULL, 'c0000000-0000-0000-0000-000000000003', 'VEHICLE_MISMATCH', 'Le vehicule au depart n''etait pas celui annonce dans l''annonce (couleur differente).', 'OPEN', NULL, NULL, NULL),
  -- IN_REVIEW : en cours de traitement par le back-office
  ('20000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000002', NULL, 'FRAUD', 'Ma reservation a ete annulee par le conducteur juste avant le depart sans explication ni remboursement.', 'IN_REVIEW', NULL, NULL, NULL),
  -- RESOLVED : traite, action prise
  ('20000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000030', 'a0000000-0000-0000-0000-000000000005', NULL, 'OTHER', 'Trajet annule tres tardivement, j''avais deja organise mon depart.', 'RESOLVED', 'Trajet annule et integralement rembourse ; rappel envoye au conducteur sur le delai de prevenance.', 'a0000000-0000-0000-0000-000000000031', now() - interval '2 days'),
  -- DISMISSED : classe sans suite
  ('20000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000026', 'a0000000-0000-0000-0000-000000000006', NULL, 'DANGEROUS_DRIVING', 'Conduite qui m''a semble dangereuse sur la nationale.', 'DISMISSED', 'Signalement isole, aucun autre temoignage, aucun element materiel : classe sans suite.', 'a0000000-0000-0000-0000-000000000031', now() - interval '6 days')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 11. LOTS DE REVERSEMENT CONDUCTEUR (migrations V1 + V5 : driver_payouts,
--     driver_payout_items — regle metier n.12, seules les reservations MoMo
--     completees generent un reversement). Depuis la migration V7, la
--     plateforme ne reverse que ce qu'elle a reellement encaisse en ligne :
--     net_amount = deposit_amount - service_fee (MOMO_DEPOSIT) ou
--     amount - service_fee (MOMO_FULL, formule inchangee). Le solde en
--     especes regle a bord (balance_due_on_board) ne transite jamais par la
--     plateforme et n'apparait donc dans aucun lot.
-- ============================================================
INSERT INTO driver_payouts (id, driver_id, amount, status, requested_at, settled_at, destination_msisdn, period_start, period_end)
VALUES
  -- Lot deja verse (historique) : 0 (d008) + 240 (d009) = 240
  ('22000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002', 240, 'SETTLED', now() - interval '7 days', now() - interval '5 days', '+22997001002', now() - interval '14 days', now() - interval '7 days'),
  -- Lot en attente de traitement par le back-office (d012, MOMO_FULL : inchange)
  ('22000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000003', 37260, 'PENDING', now() - interval '13 days', NULL, '+22997001003', now() - interval '21 days', now() - interval '14 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO driver_payout_items (id, payout_id, booking_id, net_amount)
VALUES
  -- Lot 1 (Moucharafou) : les deux reservations MoMo completees du trajet Parakou->Cotonou
  -- d008 : deposit_amount (2280) == service_fee (2280) -> net_amount = 0. Le
  -- gros de la course (28 500 FCFA) a ete regle en especes a bord et ne
  -- transite jamais par la plateforme ; celle-ci ne reverse ici que le solde
  -- de commission deja couvert par l'acompte, soit rien. Cas volontairement
  -- garde tel quel dans le jeu de demo : illustre au back-office qu'un
  -- reversement a 0 FCFA est un resultat normal du modele, pas une anomalie.
  ('23000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000008', 0),
  -- d009 : deposit_amount (1000) - service_fee (760) = 240
  ('23000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000009', 240),
  -- Lot 2 (Boni) : d012, MOMO_FULL -> amount (40500) - service_fee (3240) = 37260 (inchange)
  ('23000000-0000-0000-0000-000000000003', '22000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000012', 37260)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 12. TRACES DE RECHERCHE (migration V9 : search_events) — alimentent les
--     indicateurs de liquidite du back-office (/admin et /admin/liquidity).
--     Melange de recherches connectees (passagers a...10 a a...25) et anonymes
--     (user_id NULL), abouties ou non. Les axes vers le Nord (Natitingou,
--     Djougou, Kandi) et le retour Parakou->Cotonou restent sans resultat :
--     ce sont les "axes en penurie" que le fondateur doit demarcher.
--     origin_place_id / dest_place_id sont resolus par nom de ville dans
--     geo_places (V3), comme le ferait SearchEventService a l'ecriture.
-- ============================================================
INSERT INTO search_events (id, user_id, origin_label, origin_lat, origin_lng, origin_place_id, dest_label, dest_lat, dest_lng, dest_place_id, requested_date, seats, trip_type, radius_km, result_count, created_at)
SELECT v.id::uuid, v.user_id::uuid, v.origin_label, v.origin_lat, v.origin_lng, po.id,
       v.dest_label, v.dest_lat, v.dest_lng, pd.id,
       (now() + v.date_offset)::date, v.seats, v.trip_type, 15, v.result_count, now() - v.age
FROM (VALUES
  -- Cotonou -> Bohicon : axe qui marche (des trajets existent)
  ('13000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000011', 'Cotonou, gare Jonquet', 6.3703, 2.3912, 'Cotonou', 'Bohicon, gare routiere', 7.1786, 2.0667, 'Bohicon', interval '2 days', 2, 'INTERURBAIN', 3, interval '3 hours'),
  ('13000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000012', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Bohicon', 7.1781, 2.0672, 'Bohicon', interval '2 days', 1, 'INTERURBAIN', 3, interval '5 hours'),
  ('13000000-0000-0000-0000-000000000003', NULL, 'Cotonou, Cadjehoun', 6.3654, 2.3835, 'Cotonou', 'Bohicon', 7.1781, 2.0672, 'Bohicon', interval '3 days', 1, NULL, 2, interval '1 day'),
  ('13000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000013', 'Cotonou, Akpakpa', 6.3667, 2.4333, 'Cotonou', 'Bohicon, gare routiere', 7.1786, 2.0667, 'Bohicon', interval '5 days', 3, 'INTERURBAIN', 1, interval '2 days'),
  ('13000000-0000-0000-0000-000000000005', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Bohicon', 7.1781, 2.0672, 'Bohicon', interval '1 day', 1, 'INTERURBAIN', 0, interval '4 days'),
  -- Abomey-Calavi <-> Cotonou : navette quotidienne, forte demande
  ('13000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000018', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Abomey-Calavi', 'Cotonou, Etoile Rouge', 6.3703, 2.3912, 'Cotonou', interval '1 day', 1, 'QUOTIDIEN', 4, interval '30 minutes'),
  ('13000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000019', 'Abomey-Calavi', 6.4489, 2.3556, 'Abomey-Calavi', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '1 day', 1, 'QUOTIDIEN', 4, interval '2 hours'),
  ('13000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000020', 'Calavi, Tankpe', 6.4372, 2.3418, 'Abomey-Calavi', 'Cotonou, Ganhi', 6.3628, 2.4256, 'Cotonou', interval '2 days', 2, 'QUOTIDIEN', 2, interval '6 hours'),
  ('13000000-0000-0000-0000-000000000009', NULL, 'Abomey-Calavi', 6.4489, 2.3556, 'Abomey-Calavi', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '1 day', 1, 'QUOTIDIEN', 4, interval '1 day'),
  ('13000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000021', 'Abomey-Calavi', 6.4489, 2.3556, 'Abomey-Calavi', 'Cotonou, Cadjehoun', 6.3654, 2.3835, 'Cotonou', interval '3 days', 1, 'QUOTIDIEN', 3, interval '3 days'),
  ('13000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000018', 'Cotonou, Etoile Rouge', 6.3703, 2.3912, 'Cotonou', 'Abomey-Calavi, Godomey-Togoudo', 6.4489, 2.3556, 'Abomey-Calavi', interval '1 day', 1, 'QUOTIDIEN', 0, interval '5 days'),
  ('13000000-0000-0000-0000-000000000012', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Abomey-Calavi', 6.4489, 2.3556, 'Abomey-Calavi', interval '2 days', 1, 'QUOTIDIEN', 0, interval '6 days'),
  -- Cotonou <-> Porto-Novo
  ('13000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000014', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Porto-Novo', 6.4969, 2.6289, 'Porto-Novo', interval '2 days', 2, 'INTERURBAIN', 2, interval '8 hours'),
  ('13000000-0000-0000-0000-000000000014', NULL, 'Cotonou, Dantokpa', 6.3708, 2.4297, 'Cotonou', 'Porto-Novo, Ouando', 6.4900, 2.6200, 'Porto-Novo', interval '1 day', 1, NULL, 1, interval '2 days'),
  ('13000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000015', 'Porto-Novo', 6.4969, 2.6289, 'Porto-Novo', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '2 days', 1, 'INTERURBAIN', 0, interval '3 days'),
  -- Cotonou -> Parakou : offre presente, retour absent
  ('13000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000014', 'Cotonou, gare de Parakou', 6.3720, 2.4050, 'Cotonou', 'Parakou', 9.3372, 2.6303, 'Parakou', interval '4 days', 3, 'INTERURBAIN', 1, interval '10 hours'),
  ('13000000-0000-0000-0000-000000000017', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Parakou', 9.3372, 2.6303, 'Parakou', interval '6 days', 1, 'INTERURBAIN', 1, interval '1 day'),
  ('13000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000016', 'Parakou, gare routiere', 9.3372, 2.6303, 'Parakou', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '3 days', 2, 'INTERURBAIN', 0, interval '4 hours'),
  ('13000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000017', 'Parakou', 9.3372, 2.6303, 'Parakou', 'Cotonou, Akpakpa', 6.3667, 2.4333, 'Cotonou', interval '5 days', 1, 'INTERURBAIN', 0, interval '2 days'),
  ('13000000-0000-0000-0000-000000000020', NULL, 'Parakou', 9.3372, 2.6303, 'Parakou', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '2 days', 1, NULL, 0, interval '5 days'),
  ('13000000-0000-0000-0000-000000000021', 'a0000000-0000-0000-0000-000000000022', 'Parakou', 9.3372, 2.6303, 'Parakou', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '7 days', 1, 'INTERURBAIN', 0, interval '9 days'),
  -- Le Nord : Natitingou, Djougou, Kandi — recherche sans resultat (penurie)
  ('13000000-0000-0000-0000-000000000022', 'a0000000-0000-0000-0000-000000000023', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '5 days', 2, 'INTERURBAIN', 0, interval '1 hour'),
  ('13000000-0000-0000-0000-000000000023', NULL, 'Cotonou, Saint Michel', 6.3650, 2.4200, 'Cotonou', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '6 days', 1, 'INTERURBAIN', 0, interval '12 hours'),
  ('13000000-0000-0000-0000-000000000024', 'a0000000-0000-0000-0000-000000000024', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '8 days', 1, NULL, 0, interval '2 days'),
  ('13000000-0000-0000-0000-000000000025', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Natitingou, gare', 10.3000, 1.3800, 'Natitingou', interval '3 days', 3, 'INTERURBAIN', 0, interval '6 days'),
  ('13000000-0000-0000-0000-000000000026', 'a0000000-0000-0000-0000-000000000025', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '10 days', 1, 'INTERURBAIN', 0, interval '11 days'),
  ('13000000-0000-0000-0000-000000000027', 'a0000000-0000-0000-0000-000000000010', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Djougou', 9.7085, 1.6663, 'Djougou', interval '4 days', 1, 'INTERURBAIN', 0, interval '7 hours'),
  ('13000000-0000-0000-0000-000000000028', NULL, 'Cotonou, Zogbo', 6.3597, 2.4162, 'Cotonou', 'Djougou', 9.7085, 1.6663, 'Djougou', interval '4 days', 2, NULL, 0, interval '3 days'),
  ('13000000-0000-0000-0000-000000000029', 'a0000000-0000-0000-0000-000000000011', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Kandi', 11.1342, 2.9386, 'Kandi', interval '9 days', 1, 'INTERURBAIN', 0, interval '4 days'),
  ('13000000-0000-0000-0000-000000000030', NULL, 'Bohicon', 7.1781, 2.0672, 'Bohicon', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '5 days', 1, 'INTERURBAIN', 0, interval '8 days'),
  -- Lome et Lokossa
  ('13000000-0000-0000-0000-000000000031', 'a0000000-0000-0000-0000-000000000012', 'Cotonou, Cadjehoun', 6.3654, 2.3835, 'Cotonou', 'Lome, gare routiere', 6.1319, 1.2228, 'Lomé', interval '6 days', 1, 'INTERURBAIN', 1, interval '20 hours'),
  ('13000000-0000-0000-0000-000000000032', NULL, 'Lokossa', 6.6389, 1.7169, 'Lokossa', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '3 days', 1, 'INTERURBAIN', 0, interval '5 hours'),
  ('13000000-0000-0000-0000-000000000033', 'a0000000-0000-0000-0000-000000000013', 'Lokossa', 6.6389, 1.7169, 'Lokossa', 'Cotonou, Etoile Rouge', 6.3703, 2.3912, 'Cotonou', interval '2 days', 2, 'INTERURBAIN', 0, interval '10 days'),
  -- Periode precedente (plus de 30 jours) : sert a la variation vs periode precedente
  ('13000000-0000-0000-0000-000000000034', 'a0000000-0000-0000-0000-000000000014', 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Bohicon', 7.1781, 2.0672, 'Bohicon', interval '2 days', 1, 'INTERURBAIN', 2, interval '35 days'),
  ('13000000-0000-0000-0000-000000000035', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Parakou', 9.3372, 2.6303, 'Parakou', interval '3 days', 1, 'INTERURBAIN', 0, interval '40 days'),
  ('13000000-0000-0000-0000-000000000036', 'a0000000-0000-0000-0000-000000000018', 'Abomey-Calavi', 6.4489, 2.3556, 'Abomey-Calavi', 'Cotonou', 6.3703, 2.3912, 'Cotonou', interval '1 day', 1, 'QUOTIDIEN', 3, interval '45 days'),
  ('13000000-0000-0000-0000-000000000037', NULL, 'Cotonou', 6.3703, 2.3912, 'Cotonou', 'Natitingou', 10.3042, 1.3796, 'Natitingou', interval '4 days', 1, 'INTERURBAIN', 0, interval '50 days')
) AS v(id, user_id, origin_label, origin_lat, origin_lng, origin_city, dest_label, dest_lat, dest_lng, dest_city, date_offset, seats, trip_type, result_count, age)
LEFT JOIN geo_places po ON po.name = v.origin_city AND po.kind = 'CITY'
LEFT JOIN geo_places pd ON pd.name = v.dest_city AND pd.kind = 'CITY'
ON CONFLICT (id) DO NOTHING;

COMMIT;

-- ============================================================
-- Fin du jeu de donnees de demonstration.
-- Comptes utiles pour la demonstration commerciale (mot de passe Demo1234!) :
--   +22990000000 (Fabrice Houngbedji)   ADMIN — back-office : moderation, verifications, reversements
--   +22997001001 (Sylvestre Zannou)     conducteur Cotonou-Bohicon, avis recus, identite APPROVED
--   +22997001002 (Moucharafou Gomina)   conducteur Cotonou-Parakou (minibus), a un lot de reversement SETTLED
--   +22997001003 (Boni Kora)            conducteur Cotonou-Natitingou, a un lot de reversement PENDING
--   +22997001007 (Gildas Codjo)         conducteur, verification d'identite PENDING
--   +22997001008 (Wilfried Tossou)      conducteur navette quotidienne Calavi-Cotonou
--   +22996002010 (Koffi Dossou-Yovo)    passager, verification d'identite PENDING
--   +22996002018 (Nadege Sossou)        passagere reguliere (navette quotidienne)
--   +22996002014 (Rachidatou Idrissou)  passagere, voyage en famille
-- ============================================================
