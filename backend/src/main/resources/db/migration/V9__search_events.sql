-- Ekuiseo - V9 : trace des recherches de trajets (table search_events), socle
-- des indicateurs de liquidite du back-office (taux de recherche aboutie,
-- recherche -> reservation, axes en penurie). Voir CLAUDE.md, section
-- "Back-office : les KPI a mesurer", et AdminLiquidityService.
-- NE JAMAIS modifier V1 a V8 : toute correction passe par une nouvelle migration.
--
-- Une ligne par recherche (premiere page seulement : la pagination d'un meme
-- resultat n'est pas une nouvelle recherche). Ecrite en asynchrone par
-- SearchEventService, hors de la transaction de recherche : une panne
-- d'ecriture ne ralentit ni ne fait echouer la recherche.
--
-- Donnees personnelles : user_id (nullable, recherche anonyme) et les
-- coordonnees demandees. Conservation bornee : purge quotidienne au-dela de
-- ekuiseo.search-events.retention-days (180 jours par defaut), declaree dans
-- docs/CONFORMITE.md. ON DELETE SET NULL sur user_id : la suppression ou
-- l'anonymisation d'un compte ne casse rien et ne laisse aucun lien nominatif.
--
-- origin_place_id / dest_place_id : ville du referentiel geo_places (V3) la
-- plus proche du point recherche (resolue a l'ecriture, cf.
-- GeoPlaceRepository#findNearestCity). Sert de cle de regroupement stable pour
-- les axes ("Cotonou -> Parakou"), la ou le libelle tape par l'utilisateur
-- varie d'une recherche a l'autre ("Cotonou, gare Jonquet", "cotonou"...).

CREATE TABLE search_events (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    origin_label     VARCHAR(255),
    origin_lat       DOUBLE PRECISION NOT NULL,
    origin_lng       DOUBLE PRECISION NOT NULL,
    origin_place_id  UUID REFERENCES geo_places(id) ON DELETE SET NULL,
    dest_label       VARCHAR(255),
    dest_lat         DOUBLE PRECISION NOT NULL,
    dest_lng         DOUBLE PRECISION NOT NULL,
    dest_place_id    UUID REFERENCES geo_places(id) ON DELETE SET NULL,
    requested_date   DATE,
    seats            INTEGER NOT NULL DEFAULT 1 CHECK (seats > 0),
    trip_type        VARCHAR(20),
    radius_km        DOUBLE PRECISION NOT NULL CHECK (radius_km > 0),
    result_count     INTEGER NOT NULL CHECK (result_count >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_search_events_trip_type
        CHECK (trip_type IS NULL OR trip_type IN ('INTERURBAIN', 'QUOTIDIEN'))
);

-- Toutes les agregations du tableau de bord filtrent sur une fenetre de
-- created_at ; la purge de retention aussi.
CREATE INDEX idx_search_events_created ON search_events(created_at);
-- Attribution recherche -> reservation (meme utilisateur, sous 24 h) et
-- purge ciblee par utilisateur (droit a l'effacement).
CREATE INDEX idx_search_events_user_created ON search_events(user_id, created_at)
    WHERE user_id IS NOT NULL;
-- Regroupement par axe (origine, destination) pour les corridors en penurie.
CREATE INDEX idx_search_events_places ON search_events(origin_place_id, dest_place_id);
