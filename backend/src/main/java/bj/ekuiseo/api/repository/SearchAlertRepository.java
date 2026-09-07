package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.SearchAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SearchAlertRepository extends JpaRepository<SearchAlert, UUID> {

    List<SearchAlert> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Alertes actives d un utilisateur (dedoublonnage et plafond, TripAlertService). */
    List<SearchAlert> findByUserIdAndActiveTrueOrderByCreatedAtDesc(UUID userId);

    /** Anonymisation d un compte (UserService#anonymize). */
    void deleteByUserId(UUID userId);

    /**
     * Alertes qui correspondent a un trajet qui vient d etre publie, en UNE requete
     * (constat F526, plus de N+1) : actives, fenetre de dates couvrant le jour du depart,
     * places suffisantes, type de trajet respecte, jamais celles du conducteur lui-meme, et
     * correspondance geographique au rayon propre a chaque alerte (radius_km, V16).
     *
     * <p>Meme logique de montee/descente que la recherche (TripRepository#search, constats
     * F115/F409) : les points candidats du trajet sont son origine (position 0), ses arrets
     * intermediaires (position i) et sa destination (position 1 000 000) ; il faut un point de
     * montee dans le rayon de l origine de l alerte ET un point de descente de position
     * strictement superieure dans le rayon de sa destination. Contrainte de sens (F408) :
     * chaque point candidat doit etre plus proche de l extremite de l alerte qu il sert que de
     * l autre, sans quoi un trajet en sens inverse repondrait sur un axe court.</p>
     */
    @Query("select a from SearchAlert a where a.active = true "
            + "and (a.dateFrom is null or a.dateFrom <= :departureDate) "
            + "and (a.dateTo is null or a.dateTo >= :departureDate)")
    List<SearchAlert> findActiveCandidates(@Param("departureDate") LocalDate departureDate);

    /** Retention (RetentionScheduler, constat F525) : une alerte datee s eteint une fois sa fenetre passee. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SearchAlert a set a.active = false where a.active = true and a.dateTo is not null and a.dateTo < :today")
    int deactivateExpired(@Param("today") LocalDate today);

    /** Retention : alertes inactives creees avant la date. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SearchAlert a where a.active = false and a.createdAt < :before")
    int deleteInactiveCreatedBefore(@Param("before") Instant before);

    @Query(value = """
            with pts as (
                select t.origin_point as point, 0 as position from trips t where t.id = :tripId
                union all
                select s.point, s.position from trip_stops s where s.trip_id = :tripId
                union all
                select t.dest_point, 1000000 from trips t where t.id = :tripId
            )
            select a.* from search_alerts a
            where a.active = true
              and (a.date_from is null or a.date_from <= cast(:departureDate as date))
              and (a.date_to is null or a.date_to >= cast(:departureDate as date))
              and a.seats <= :seatsAvailable
              and (a.trip_type is null or a.trip_type = cast(:tripType as varchar))
              and a.user_id <> :driverId
              and exists (
                  select 1
                  from pts p1
                  join pts p2 on p2.position > p1.position
                  where ST_DWithin(p1.point, ST_SetSRID(ST_MakePoint(a.origin_lng, a.origin_lat), 4326)::geography, a.radius_km * 1000)
                    and ST_DWithin(p2.point, ST_SetSRID(ST_MakePoint(a.dest_lng, a.dest_lat), 4326)::geography, a.radius_km * 1000)
                    and ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(a.origin_lng, a.origin_lat), 4326)::geography)
                      < ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(a.dest_lng, a.dest_lat), 4326)::geography)
                    and ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(a.dest_lng, a.dest_lat), 4326)::geography)
                      < ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(a.origin_lng, a.origin_lat), 4326)::geography)
              )
            order by a.created_at
            """, nativeQuery = true)
    List<SearchAlert> findMatching(@Param("tripId") UUID tripId,
                                   @Param("driverId") UUID driverId,
                                   @Param("departureDate") LocalDate departureDate,
                                   @Param("seatsAvailable") int seatsAvailable,
                                   @Param("tripType") String tripType);
}
