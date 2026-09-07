package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.GeoPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeoPlaceRepository extends JpaRepository<GeoPlace, UUID> {

    /**
     * Ville (kind = CITY, jamais un quartier ni une gare) du referentiel la plus proche
     * d'un point, a moins de {@code maxMeters}. Sert de cle de regroupement stable des
     * axes pour les traces de recherche (SearchEventService) : "Cotonou -> Parakou" quel
     * que soit le libelle tape. Le referentiel compte quelques dizaines de lignes : un
     * calcul de distance ligne a ligne suffit, aucun index spatial n'est necessaire.
     */
    @Query(value = """
            select * from geo_places p
            where p.kind = 'CITY'
              and ST_DWithin(ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)::geography,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :maxMeters)
            order by ST_Distance(ST_SetSRID(ST_MakePoint(p.lng, p.lat), 4326)::geography,
                                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
            limit 1
            """, nativeQuery = true)
    Optional<GeoPlace> findNearestCity(@Param("lat") double lat, @Param("lng") double lng,
                                       @Param("maxMeters") double maxMeters);

    /**
     * Recherche insensible a la casse et aux accents (extension Postgres "unaccent",
     * installee en migration V3) sur le prefixe ou une sous-chaine du nom du lieu ou de
     * l un de ses alias (V17, deja normalises). Les correspondances de prefixe sont
     * classees avant les correspondances de sous-chaine, puis les villes avant les
     * quartiers et gares, puis par nom.
     */
    @Query(value = """
            select * from geo_places g
            where g.normalized_name like '%' || unaccent(lower(:q)) || '%'
               or exists (select 1 from unnest(g.aliases) a where a like '%' || unaccent(lower(:q)) || '%')
            order by
              case when g.normalized_name like unaccent(lower(:q)) || '%' then 0 else 1 end,
              case when g.kind = 'CITY' then 0 else 1 end,
              g.name
            limit :limit
            """, nativeQuery = true)
    List<GeoPlace> search(@Param("q") String query, @Param("limit") int limit);

    /**
     * Repli tolerant aux fautes (constat F421, extension pg_trgm et index GIN de V17) quand
     * la recherche exacte ne renvoie rien : « natitngou » trouve Natitingou. Seuil de
     * similarite 0,3 (celui de pg_trgm), meilleures correspondances d abord.
     */
    @Query(value = """
            select * from geo_places g
            where similarity(g.normalized_name, unaccent(lower(:q))) > 0.3
            order by similarity(g.normalized_name, unaccent(lower(:q))) desc,
              case when g.kind = 'CITY' then 0 else 1 end,
              g.name
            limit :limit
            """, nativeQuery = true)
    List<GeoPlace> searchFuzzy(@Param("q") String query, @Param("limit") int limit);

    /** Referentiel complet (GET /api/v1/geo/places) : villes d abord (CITY < DISTRICT < STATION, ordre alphabetique du kind), puis par nom. */
    List<GeoPlace> findAllByOrderByKindAscNameAsc();
}
