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
     * Ville (kind = CITY, jamais un quartier) du referentiel la plus proche d'un point,
     * a moins de {@code maxMeters}. Sert de cle de regroupement stable des axes pour
     * les traces de recherche (SearchEventService) : "Cotonou -> Parakou" quel que
     * soit le libelle tape. Le referentiel compte quelques dizaines de lignes : un
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
     * installee en migration V3) sur le prefixe ou une sous-chaine du nom du lieu.
     * Les correspondances de prefixe sont classees avant les correspondances de
     * sous-chaine, puis par nom.
     */
    @Query(value = """
            select * from geo_places
            where normalized_name like '%' || unaccent(lower(:q)) || '%'
            order by
              case when normalized_name like unaccent(lower(:q)) || '%' then 0 else 1 end,
              name
            limit :limit
            """, nativeQuery = true)
    List<GeoPlace> search(@Param("q") String query, @Param("limit") int limit);
}
