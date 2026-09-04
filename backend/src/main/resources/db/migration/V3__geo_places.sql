-- Ekuiseo - V3 : referentiel de geocodage (villes et quartiers du Benin, plus
-- Lome et Lagos pour le trafic transfrontalier), servant de cache en base pour
-- GeocodingService (recherche insensible a la casse et aux accents, cf. l'index
-- ci-dessous et GeoPlaceRepository#search).
--
-- Coordonnees : approximatives et plausibles (arrondies au 1/10 000e de degre),
-- destinees a alimenter la recherche/autocompletion et des demonstrations, PAS
-- des releves geodesiques verifies. A affiner si une precision cadastrale est
-- necessaire (ex : bornage GPS reel de chaque gare routiere).

CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE geo_places (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    region          VARCHAR(100),
    country_code    VARCHAR(2) NOT NULL DEFAULT 'BJ',
    kind            VARCHAR(20) NOT NULL,
    parent_place_id UUID REFERENCES geo_places(id),
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    CONSTRAINT chk_geo_places_kind CHECK (kind IN ('CITY', 'DISTRICT'))
);

-- Recherche par prefixe/sous-chaine sur le nom normalise (voir GeoPlaceRepository#search).
CREATE INDEX idx_geo_places_normalized_name ON geo_places(normalized_name);
CREATE INDEX idx_geo_places_parent ON geo_places(parent_place_id);

-- ============================================================
-- Villes principales du Benin (+ Lome et Lagos, trafic transfrontalier)
-- ============================================================
INSERT INTO geo_places (name, normalized_name, region, country_code, kind, lat, lng) VALUES
    ('Cotonou',       unaccent(lower('Cotonou')),       'Littoral',    'BJ', 'CITY', 6.3703,  2.3912),
    ('Porto-Novo',    unaccent(lower('Porto-Novo')),    'Ouémé',       'BJ', 'CITY', 6.4969,  2.6289),
    ('Abomey-Calavi', unaccent(lower('Abomey-Calavi')), 'Atlantique',  'BJ', 'CITY', 6.4489,  2.3556),
    ('Bohicon',       unaccent(lower('Bohicon')),       'Zou',         'BJ', 'CITY', 7.1781,  2.0672),
    ('Abomey',        unaccent(lower('Abomey')),        'Zou',         'BJ', 'CITY', 7.1826,  1.9910),
    ('Parakou',       unaccent(lower('Parakou')),       'Borgou',      'BJ', 'CITY', 9.3372,  2.6303),
    ('Natitingou',    unaccent(lower('Natitingou')),    'Atacora',     'BJ', 'CITY', 10.3042, 1.3796),
    ('Djougou',       unaccent(lower('Djougou')),       'Donga',       'BJ', 'CITY', 9.7085,  1.6663),
    ('Lokossa',       unaccent(lower('Lokossa')),       'Mono',        'BJ', 'CITY', 6.6389,  1.7169),
    ('Ouidah',        unaccent(lower('Ouidah')),        'Atlantique',  'BJ', 'CITY', 6.3626,  2.0852),
    ('Kandi',         unaccent(lower('Kandi')),         'Alibori',     'BJ', 'CITY', 11.1342, 2.9386),
    ('Malanville',    unaccent(lower('Malanville')),    'Alibori',     'BJ', 'CITY', 11.8636, 3.3862),
    ('Savalou',       unaccent(lower('Savalou')),       'Collines',    'BJ', 'CITY', 7.9285,  1.9739),
    ('Comè',          unaccent(lower('Comè')),          'Mono',        'BJ', 'CITY', 6.4056,  1.8836),
    ('Grand-Popo',    unaccent(lower('Grand-Popo')),    'Mono',        'BJ', 'CITY', 6.2833,  1.8167),
    ('Lomé',          unaccent(lower('Lomé')),          NULL,          'TG', 'CITY', 6.1319,  1.2228),
    ('Lagos',         unaccent(lower('Lagos')),         NULL,          'NG', 'CITY', 6.5244,  3.3792);

-- ============================================================
-- Quartiers de Cotonou (kind=DISTRICT, rattaches a la ville via parent_place_id)
-- ============================================================
INSERT INTO geo_places (name, normalized_name, region, country_code, kind, parent_place_id, lat, lng)
SELECT v.name, unaccent(lower(v.name)), 'Littoral', 'BJ', 'DISTRICT', c.id, v.lat, v.lng
FROM (VALUES
    ('Cadjehoun', 6.3654, 2.3835),
    ('Akpakpa',   6.3667, 2.4333),
    ('Fidjrossè', 6.3489, 2.3733),
    ('Gbégamey',  6.3833, 2.4022),
    ('Sainte Rita', 6.3706, 2.4034),
    ('Zogbo',     6.3597, 2.4162),
    ('Vêdoko',    6.3856, 2.3765),
    ('Dantokpa',  6.3708, 2.4297),
    ('Ganhi',     6.3628, 2.4256),
    ('Agla',      6.3931, 2.3892)
) AS v(name, lat, lng)
CROSS JOIN (SELECT id FROM geo_places WHERE name = 'Cotonou' AND kind = 'CITY') AS c;
