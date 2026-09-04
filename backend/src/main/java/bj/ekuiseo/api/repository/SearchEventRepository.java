package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.SearchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Trace des recherches (migration V9). Lecture exclusivement par agregation SQL :
 * ce depot ne charge jamais la liste des evenements en memoire, quelle que soit
 * la periode (une journee active peut representer des milliers de lignes).
 *
 * <p>Alias SQL en snake_case (jamais camelCase) : Postgres replie tout alias non
 * quote en minuscules, et le mapping de Spring Data vers une projection par
 * interface repose sur la convention underscore -> camelCase pour retrouver les
 * accesseurs. Les horodatages sont renvoyes en secondes epoch (double) plutot
 * qu'en timestamptz, pour ne pas dependre du type Java exact que Hibernate choisit
 * pour un timestamptz natif (OffsetDateTime vs Instant).</p>
 */
public interface SearchEventRepository extends JpaRepository<SearchEvent, UUID> {

    /** Entonnoir de recherche sur une periode, voir {@link #getFunnelStats}. */
    interface FunnelStats {
        long getTotal();

        long getWithResults();

        long getByUsers();

        long getConverted();
    }

    /**
     * Entonnoir de recherche sur [from, to) : recherches totales, ayant renvoye au
     * moins un trajet, faites par un utilisateur connecte, et "converties".
     *
     * <p>Attribution recherche -> reservation, par approximation assumee : une
     * recherche d'un utilisateur connecte est dite convertie si CE MEME utilisateur
     * a cree une reservation (quel que soit son statut final) dans les 24 heures
     * qui suivent. Les recherches anonymes ne peuvent pas etre attribuees et ne
     * comptent que dans {@code total}/{@code withResults} ; le taux de conversion se
     * calcule donc sur {@code byUsers}, pas sur {@code total}.</p>
     */
    @Query(value = """
            select
              count(*) as total,
              count(*) filter (where s.result_count > 0) as with_results,
              count(*) filter (where s.user_id is not null) as by_users,
              count(*) filter (where s.user_id is not null and exists (
                  select 1 from bookings b
                  where b.passenger_id = s.user_id
                    and b.created_at >= s.created_at
                    and b.created_at < s.created_at + interval '24 hours')) as converted
            from search_events s
            where s.created_at >= :from and s.created_at < :to
            """, nativeQuery = true)
    FunnelStats getFunnelStats(@Param("from") Instant from, @Param("to") Instant to);

    /** Axe recherche sans resultat, voir {@link #findShortageRoutes}. */
    interface ShortageRoute {
        String getOrigin();

        String getDestination();

        long getSearches();

        long getWithoutResults();

        /** Derniere recherche sur cet axe, en secondes epoch (a convertir en Instant cote service). */
        Double getLastSearchedEpoch();
    }

    /**
     * Axes (origine, destination) les plus recherches SANS resultat sur [from, to) :
     * la liste des corridors a demarcher en priorite aupres des conducteurs.
     *
     * <p>Cle de regroupement : la ville geo_places resolue a l'ecriture, a defaut le
     * libelle tape, a defaut les coordonnees arrondies au centieme de degre (~1 km).
     * Classement par nombre de recherches infructueuses, puis par volume total.</p>
     */
    @Query(value = """
            select
              coalesce(po.name, s.origin_label,
                       round(s.origin_lat::numeric, 2) || ', ' || round(s.origin_lng::numeric, 2)) as origin,
              coalesce(pd.name, s.dest_label,
                       round(s.dest_lat::numeric, 2) || ', ' || round(s.dest_lng::numeric, 2)) as destination,
              count(*) as searches,
              count(*) filter (where s.result_count = 0) as without_results,
              extract(epoch from max(s.created_at)) as last_searched_epoch
            from search_events s
            left join geo_places po on po.id = s.origin_place_id
            left join geo_places pd on pd.id = s.dest_place_id
            where s.created_at >= :from and s.created_at < :to
            group by 1, 2
            having count(*) filter (where s.result_count = 0) > 0
            order by without_results desc, searches desc, origin, destination
            limit :limit
            """, nativeQuery = true)
    List<ShortageRoute> findShortageRoutes(@Param("from") Instant from, @Param("to") Instant to,
                                            @Param("limit") int limit);

    /** Purge de retention (voir SearchEventRetentionScheduler et docs/CONFORMITE.md). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SearchEvent s where s.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
