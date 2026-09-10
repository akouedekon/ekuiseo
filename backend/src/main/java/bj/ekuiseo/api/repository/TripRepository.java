package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    List<Trip> findByDriverIdOrderByDepartureAtDesc(UUID driverId);

    /** Trajets d un conducteur, pagines (fiche utilisateur du back-office, GET /api/v1/admin/users/{id}/trips). */
    Page<Trip> findByDriverIdOrderByDepartureAtDesc(UUID driverId, Pageable pageable);

    long countByDriverIdAndStatus(UUID driverId, bj.ekuiseo.api.domain.enums.TripStatus status);

    long countByDriverId(UUID driverId);

    /** Utilisee par DELETE /api/v1/me/vehicles/{id} : refuse la suppression d'un vehicule deja engage. */
    boolean existsByVehicleIdAndStatusInAndDepartureAtAfter(UUID vehicleId,
            List<bj.ekuiseo.api.domain.enums.TripStatus> statuses, Instant departureAfter);

    /** Prochaine occurrence a venir d'un trajet recurrent, pour GET /api/v1/me/recurring-trips. */
    Optional<Trip> findFirstByParentTripIdAndStatusAndDepartureAtAfterOrderByDepartureAtAsc(
            UUID parentTripId, bj.ekuiseo.api.domain.enums.TripStatus status, Instant departureAfter);

    /**
     * Prochaine offre disponible sur le meme axe (libelles origine/destination),
     * utilisee par l'heuristique "trajet recurrent du passager" (GET
     * /api/v1/me/recurring-trips, voir BookingService#myRecurringTrips) : ce
     * n'est PAS le meme mecanisme que parent_trip_id/recurrence_rule ci-dessus
     * (qui concerne les trajets publies par un CONDUCTEUR recurrent), mais une
     * detection cote passager basee sur son historique de reservations.
     */
    Optional<Trip> findFirstByOriginLabelAndDestLabelAndStatusAndDepartureAtAfterAndSeatsAvailableGreaterThanOrderByDepartureAtAsc(
            String originLabel, String destLabel, bj.ekuiseo.api.domain.enums.TripStatus status,
            Instant departureAfter, int minSeatsAvailable);

    long countByOriginLabelAndDestLabelAndStatusAndDepartureAtAfterAndSeatsAvailableGreaterThan(
            String originLabel, String destLabel, bj.ekuiseo.api.domain.enums.TripStatus status,
            Instant departureAfter, int minSeatsAvailable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") UUID id);

    /** Lien public de suivi en direct (GET /api/v1/live/{token}, V23). */
    Optional<Trip> findByLiveShareToken(String liveShareToken);

    /**
     * Purge nocturne du suivi en direct (RetentionScheduler, V23) : coupe le partage et efface
     * le jeton des trajets termines ou annules depuis plus longtemps que la retention des
     * positions, pour que le lien public cesse de repondre.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.liveSharingEnabled = false, t.liveShareToken = null "
            + "where (t.liveSharingEnabled = true or t.liveShareToken is not null) "
            + "and t.status in :statuses and t.updatedAt < :before")
    int disableLiveSharingForStatusesUpdatedBefore(@Param("statuses") List<bj.ekuiseo.api.domain.enums.TripStatus> statuses,
                                                    @Param("before") Instant before);

    /**
     * Decrementation atomique des places disponibles. La clause WHERE garantit,
     * au niveau de la base de donnees, qu'on ne decremente jamais en dessous de
     * zero meme en cas de reservations concurrentes sur la derniere place
     * (regle metier n.1). Retourne le nombre de lignes affectees : 0 = echec
     * (plus assez de places), 1 = succes.
     */
    // clearAutomatically = true est essentiel ici : sans cela, une entite Trip deja
    // chargee plus tot DANS LA MEME TRANSACTION resterait en cache de premier niveau
    // (first-level cache) avec son ancienne valeur de seatsAvailable, et un
    // findById() ulterieur dans la meme transaction renverrait cette valeur perimee
    // au lieu de celle ecrite par cet UPDATE en masse. flushAutomatically = true
    // garantit en plus que les modifications en attente sont ecrites avant l'UPDATE.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.seatsAvailable = t.seatsAvailable - :seats " +
            "where t.id = :id and t.seatsAvailable >= :seats")
    int decrementSeatsIfAvailable(@Param("id") UUID id, @Param("seats") int seats);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.seatsAvailable = least(t.seatsTotal, t.seatsAvailable + :seats) " +
            "where t.id = :id")
    int incrementSeats(@Param("id") UUID id, @Param("seats") int seats);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.status = :status where t.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") bj.ekuiseo.api.domain.enums.TripStatus status);

    List<Trip> findByRecurrenceRuleIsNotNullAndStatus(bj.ekuiseo.api.domain.enums.TripStatus status);

    boolean existsByParentTripIdAndDepartureAt(UUID parentTripId, Instant departureAt);

    /** Occurrences deja engendrees par un modele, tous statuts confondus (plafond COUNT de la recurrence). */
    long countByParentTripId(UUID parentTripId);

    /** Occurrences a venir d un modele, pour la cascade d annulation / de mise a jour. */
    List<Trip> findByParentTripIdAndStatusInAndDepartureAtAfter(UUID parentTripId,
            List<bj.ekuiseo.api.domain.enums.TripStatus> statuses, Instant departureAfter);

    /** Trajets a venir d un conducteur, pour la cascade de suspension. */
    List<Trip> findByDriverIdAndStatusInAndDepartureAtAfter(UUID driverId,
            List<bj.ekuiseo.api.domain.enums.TripStatus> statuses, Instant departureAfter);

    /** Cycle de vie (TripLifecycleScheduler) : trajets dans ces statuts dont le depart est anterieur a before. */
    List<Trip> findByStatusInAndDepartureAtBefore(List<bj.ekuiseo.api.domain.enums.TripStatus> statuses, Instant before);

    /** Modeles de navette actifs (recurrence), pour la generation des occurrences. */
    List<Trip> findByRecurrenceRuleIsNotNullAndParentTripIdIsNullAndStatus(bj.ekuiseo.api.domain.enums.TripStatus status);

    /** Trajets a venir (PUBLISHED ou FULL, constat F108 : un trajet complet est celui qui a le plus de passagers a rappeler) qui partent dans la fenetre [from, to) et n ont pas encore recu leur rappel (regle metier n.10). */
    @Query("select t from Trip t where t.status in (bj.ekuiseo.api.domain.enums.TripStatus.PUBLISHED, "
            + "bj.ekuiseo.api.domain.enums.TripStatus.FULL) "
            + "and t.reminderSentAt is null and t.departureAt between :from and :to")
    List<Trip> findDueForReminder(@Param("from") Instant from, @Param("to") Instant to);

    /** Conditionnel (reminder_sent_at encore null) : 0 ligne = deja marque par un autre passage, ne pas renvoyer (constat F128). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.reminderSentAt = :now where t.id = :id and t.reminderSentAt is null")
    int markReminderSent(@Param("id") UUID id, @Param("now") Instant now);

    long countByCreatedAtBetween(Instant from, Instant to);

    /** Trajets crees sur [from, to) (tableau de bord admin, une seule requete de comptage). */
    @Query("select count(t) from Trip t where t.createdAt >= :from and t.createdAt < :to")
    long countCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Compte par jour civil, pour {@link #countCreatedByDay}. */
    interface DayCount {
        /** Jour au format ISO AAAA-MM-JJ (formate en SQL, jour civil du Benin). */
        String getDay();

        long getCount();
    }

    /**
     * Trajets crees par jour civil du Benin sur [from, to) : serie du tableau de bord admin
     * (AdminStatsService, constats F016/F238), agregee en SQL plutot qu en chargeant les
     * horodatages en memoire.
     */
    @Query(value = """
            select to_char(date_trunc('day', t.created_at at time zone 'Africa/Porto-Novo'), 'YYYY-MM-DD') as day,
                   count(*) as count
            from trips t
            where t.created_at >= :from and t.created_at < :to
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DayCount> countCreatedByDay(@Param("from") Instant from, @Param("to") Instant to);

    /** Compte par identifiant, pour {@link #countByDriverIds}. */
    interface IdCount {
        UUID getId();

        long getCount();
    }

    /** Trajets publies par conducteur, pour une liste d identifiants (back-office, plus de N+1 : constats F016/F308). */
    @Query("select t.driver.id as id, count(t) as count from Trip t where t.driver.id in :ids group by t.driver.id")
    List<IdCount> countByDriverIds(@Param("ids") List<UUID> ids);

    /**
     * Trajet PUBLISHED avec au moins une place (constat F535) correspondant a une alerte de
     * recherche (regle metier n.13) : meme logique de montee/descente que {@link #search}
     * (constats F115/F409) - les points
     * candidats sont l origine (position 0), les arrets intermediaires et la destination
     * (position 1 000 000) ; il faut un point de montee dans le rayon de l origine de
     * l alerte ET un point de descente de position strictement superieure dans le rayon de
     * sa destination, chacun plus proche de l extremite qu il sert que de l autre (sens,
     * constat F408). Conservee pour les verifications ponctuelles ; le matching de masse
     * passe par SearchAlertRepository#findMatching (une requete pour toutes les alertes).
     */
    @Query(value = """
            with pts as (
                select t.origin_point as point, 0 as position from trips t where t.id = :tripId
                union all
                select s.point, s.position from trip_stops s where s.trip_id = :tripId
                union all
                select t.dest_point, 1000000 from trips t where t.id = :tripId
            )
            select t.* from trips t
            where t.id = :tripId
              and t.status = 'PUBLISHED'
              and t.seats_available > 0
              and exists (
                  select 1
                  from pts p1
                  join pts p2 on p2.position > p1.position
                  where ST_DWithin(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
                    and ST_DWithin(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
                    and ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
                      < ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                    and ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                      < ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
              )
            """, nativeQuery = true)
    List<Trip> matchesAlertGeography(@Param("tripId") UUID tripId,
                                      @Param("originLat") double originLat, @Param("originLng") double originLng,
                                      @Param("destLat") double destLat, @Param("destLng") double destLng,
                                      @Param("radiusMeters") double radiusMeters);

    /**
     * Recherche geospatiale (constats F115/F409/F137/F408).
     *
     * <p>Points candidats de chaque trajet : son origine (position 0), ses arrets
     * intermediaires ({@code trip_stops.point}, position i) et sa destination (position
     * 1 000 000). Un trajet correspond s il existe un point de montee a moins de
     * {@code radiusMeters} de l origine cherchee ET un point de descente de position
     * strictement superieure a moins de {@code radiusMeters} de la destination cherchee,
     * chacun plus proche de l extremite qu il sert que de l autre (sens : un trajet
     * Calavi -> Cotonou ne repond pas a une recherche Cotonou -> Calavi). Le troncon
     * apparie (montee, descente, prix) est calcule apres coup par TripService.</p>
     *
     * <p>{@code cand} preselectionne, par les index GIST, les trajets a venir dont un point
     * est proche de l origine cherchee ; {@code pts}/{@code matched} n enumerent que leurs
     * points. Filtres : type, jour civil, prix maximal (trajet), note minimale et identite
     * verifiee du conducteur. Tri : {@code sort} vaut DEPARTURE, PRICE ou RATING (constante
     * liee, jamais concatenee), puis pertinence (distance cumulee - bonus note) et depart.</p>
     */
    @Query(value = """
            with cand as (
                select t.id
                from trips t
                where t.status = 'PUBLISHED'
                  and t.departure_at >= :now
                  and t.seats_available >= :seats
                  and (cast(:tripType as varchar) is null or t.trip_type = cast(:tripType as varchar))
                  and (cast(:dateFrom as timestamptz) is null or t.departure_at >= cast(:dateFrom as timestamptz))
                  and (cast(:dateTo as timestamptz) is null or t.departure_at < cast(:dateTo as timestamptz))
                  and (cast(:maxPrice as bigint) is null or t.price_per_seat <= cast(:maxPrice as bigint))
                  and (cast(:vehicleType as varchar) is null
                       or exists (select 1 from vehicles v where v.id = t.vehicle_id and v.vehicle_type = cast(:vehicleType as varchar)))
                  and (ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
                       or exists (select 1 from trip_stops s where s.trip_id = t.id
                                  and ST_DWithin(s.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)))
            ),
            pts as (
                select t.id as trip_id, t.origin_point as point, 0 as position from trips t join cand c on c.id = t.id
                union all
                select s.trip_id, s.point, s.position from trip_stops s join cand c on c.id = s.trip_id
                union all
                select t.id, t.dest_point, 1000000 from trips t join cand c on c.id = t.id
            ),
            matched as (
                select distinct p1.trip_id
                from pts p1
                join pts p2 on p2.trip_id = p1.trip_id and p2.position > p1.position
                where ST_DWithin(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
                  and ST_DWithin(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
                  and ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
                    < ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                  and ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                    < ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
            )
            select t.* from trips t
            join users d on d.id = t.driver_id
            join matched m on m.trip_id = t.id
            where d.status = 'ACTIVE'
              and (cast(:minRating as numeric) is null or d.rating_avg >= cast(:minRating as numeric))
              and (cast(:verifiedOnly as boolean) = false or d.identity_verified = true)
            order by
              case when cast(:sort as varchar) = 'PRICE' then t.price_per_seat end asc,
              case when cast(:sort as varchar) = 'RATING' then coalesce(d.rating_avg, 0) end desc,
              case when cast(:sort as varchar) = 'DEPARTURE' then t.departure_at end asc,
              (ST_Distance(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
               + ST_Distance(t.dest_point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography))
              - (coalesce(d.rating_avg, 0) * 500) asc,
              t.departure_at asc
            """,
            countQuery = """
            with cand as (
                select t.id
                from trips t
                where t.status = 'PUBLISHED'
                  and t.departure_at >= :now
                  and t.seats_available >= :seats
                  and (cast(:tripType as varchar) is null or t.trip_type = cast(:tripType as varchar))
                  and (cast(:dateFrom as timestamptz) is null or t.departure_at >= cast(:dateFrom as timestamptz))
                  and (cast(:dateTo as timestamptz) is null or t.departure_at < cast(:dateTo as timestamptz))
                  and (cast(:maxPrice as bigint) is null or t.price_per_seat <= cast(:maxPrice as bigint))
                  and (cast(:vehicleType as varchar) is null
                       or exists (select 1 from vehicles v where v.id = t.vehicle_id and v.vehicle_type = cast(:vehicleType as varchar)))
                  and (ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
                       or exists (select 1 from trip_stops s where s.trip_id = t.id
                                  and ST_DWithin(s.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)))
            ),
            pts as (
                select t.id as trip_id, t.origin_point as point, 0 as position from trips t join cand c on c.id = t.id
                union all
                select s.trip_id, s.point, s.position from trip_stops s join cand c on c.id = s.trip_id
                union all
                select t.id, t.dest_point, 1000000 from trips t join cand c on c.id = t.id
            ),
            matched as (
                select distinct p1.trip_id
                from pts p1
                join pts p2 on p2.trip_id = p1.trip_id and p2.position > p1.position
                where ST_DWithin(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
                  and ST_DWithin(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
                  and ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
                    < ST_Distance(p1.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                  and ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography)
                    < ST_Distance(p2.point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
            )
            select count(*) from trips t
            join users d on d.id = t.driver_id
            join matched m on m.trip_id = t.id
            where d.status = 'ACTIVE'
              and (cast(:minRating as numeric) is null or d.rating_avg >= cast(:minRating as numeric))
              and (cast(:verifiedOnly as boolean) = false or d.identity_verified = true)
              and cast(:sort as varchar) is not null
            """,
            nativeQuery = true)
    Page<Trip> search(@Param("originLat") double originLat,
                       @Param("originLng") double originLng,
                       @Param("destLat") double destLat,
                       @Param("destLng") double destLng,
                       @Param("radiusMeters") double radiusMeters,
                       @Param("seats") int seats,
                       @Param("tripType") String tripType,
                       @Param("dateFrom") Instant dateFrom,
                       @Param("dateTo") Instant dateTo,
                       @Param("now") Instant now,
                       @Param("sort") String sort,
                       @Param("maxPrice") Long maxPrice,
                       @Param("minRating") Double minRating,
                       @Param("verifiedOnly") boolean verifiedOnly,
                       @Param("vehicleType") String vehicleType,
                       Pageable pageable);

    /** Depart proche d un point et son point de montee le plus proche, pour {@link #findNearby}. */
    interface NearbyTripRow {
        /** Identifiant du trajet, en texte (cast en SQL : projection native portable). */
        String getTripId();

        /** Distance (metres) entre le point cherche et le point de montee le plus proche. */
        double getDistanceM();

        String getBoardingLabel();

        double getBoardingLat();

        double getBoardingLng();
    }

    /**
     * Departs autour d un point (GET /api/v1/trips/nearby, ecran « Autour de moi »). Meme
     * esprit que {@link #search} : {@code cand} preselectionne, par les index GIST, les
     * trajets PUBLISHED a venir avec au moins une place dont l origine OU un arret
     * intermediaire est a moins de {@code radiusMeters} du point ; {@code pts} n enumere que
     * leurs points de montee (origine, position 0, et arrets ; jamais la destination : on n y
     * monte pas) ; {@code nearest} garde, par trajet, le point le plus proche. Conducteur
     * ACTIVE, filtre de type de vehicule comme dans la recherche. Tri : distance puis depart.
     * Aucune trace dans search_events : ce n est pas une recherche d axe.
     */
    @Query(value = """
            with cand as (
                select t.id
                from trips t
                where t.status = 'PUBLISHED'
                  and t.departure_at >= :now
                  and t.seats_available > 0
                  and (cast(:vehicleType as varchar) is null
                       or exists (select 1 from vehicles v where v.id = t.vehicle_id and v.vehicle_type = cast(:vehicleType as varchar)))
                  and (ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
                       or exists (select 1 from trip_stops s where s.trip_id = t.id
                                  and ST_DWithin(s.point, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)))
            ),
            pts as (
                select t.id as trip_id, t.origin_label as label, t.origin_lat as lat, t.origin_lng as lng, 0 as position,
                       ST_Distance(t.origin_point, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) as distance_m
                from trips t join cand c on c.id = t.id
                union all
                select s.trip_id, s.label, s.lat, s.lng, s.position,
                       ST_Distance(s.point, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
                from trip_stops s join cand c on c.id = s.trip_id
            ),
            nearest as (
                select distinct on (p.trip_id) p.trip_id, p.label, p.lat, p.lng, p.distance_m
                from pts p
                where p.distance_m <= :radiusMeters
                order by p.trip_id, p.distance_m asc, p.position asc
            )
            select cast(n.trip_id as varchar) as trip_id,
                   n.distance_m as distance_m,
                   n.label as boarding_label,
                   n.lat as boarding_lat,
                   n.lng as boarding_lng
            from nearest n
            join trips t on t.id = n.trip_id
            join users d on d.id = t.driver_id
            where d.status = 'ACTIVE'
            order by n.distance_m asc, t.departure_at asc
            limit :limit
            """, nativeQuery = true)
    List<NearbyTripRow> findNearby(@Param("lat") double lat,
                                   @Param("lng") double lng,
                                   @Param("radiusMeters") double radiusMeters,
                                   @Param("vehicleType") String vehicleType,
                                   @Param("now") Instant now,
                                   @Param("limit") int limit);

    /** Axe propose en ce moment, pour {@link #findPopularRoutes}. */
    interface PopularRouteStats {
        String getOriginLabel();

        String getDestLabel();

        Double getOriginLat();

        Double getOriginLng();

        Double getDestLat();

        Double getDestLng();

        long getTrips();

        long getMinPrice();
    }

    /**
     * Axes les plus proposes (GET /api/v1/trips/popular) : trajets PUBLISHED a venir
     * avec au moins une place, regroupes par libelles exacts d'origine/destination,
     * classes par nombre de departs puis prix plancher. Coordonnees moyennees pour
     * pre-remplir une recherche depuis l'accueil.
     */
    @Query(value = """
            select t.origin_label as origin_label,
                   t.dest_label as dest_label,
                   avg(t.origin_lat) as origin_lat,
                   avg(t.origin_lng) as origin_lng,
                   avg(t.dest_lat) as dest_lat,
                   avg(t.dest_lng) as dest_lng,
                   count(*) as trips,
                   min(t.price_per_seat) as min_price
            from trips t
            where t.status = 'PUBLISHED'
              and t.departure_at >= :now
              and t.seats_available > 0
            group by t.origin_label, t.dest_label
            order by trips desc, min_price asc
            limit :limit
            """, nativeQuery = true)
    List<PopularRouteStats> findPopularRoutes(@Param("now") Instant now, @Param("limit") int limit);

    // ------------------------------------------------------------------
    // Indicateurs de liquidite (AdminLiquidityService). Requetes natives
    // agregees : jamais de chargement de trajets en memoire pour compter.
    // Alias en snake_case (voir la note dans MessageRepository).
    // ------------------------------------------------------------------

    /** Remplissage d'un mode de trajet, pour {@link #getFillStatsByMode}. */
    interface ModeFillStats {
        String getTripType();

        long getTrips();

        long getSeatsPublished();

        long getSeatsBooked();

        long getOrphanTrips();
    }

    /** Remplissage d'un axe, pour {@link #getFillStatsByRoute}. */
    interface RouteFillStats {
        String getOrigin();

        String getDestination();

        String getTripType();

        long getTrips();

        long getSeatsPublished();

        long getSeatsBooked();

        long getOrphanTrips();
    }

    /** Delai publication -> premiere reservation, pour {@link #getFirstBookingDelayStats}. */
    interface FirstBookingDelayStats {
        Double getMedianHours();

        long getSampleSize();
    }

    /**
     * Taux de remplissage par mode : trajets partis sur [from, to) (hors DRAFT et
     * CANCELLED), places publiees, places reservees dans les statuts donnes (noms
     * d'enum BookingStatus, en pratique les places reellement vendues : CONFIRMED,
     * COMPLETED, NO_SHOW) et trajets orphelins (aucune place vendue).
     * La fenetre porte sur la date de DEPART : un trajet encore a venir n'est ni
     * "rempli" ni "orphelin", il est en cours de remplissage.
     */
    @Query(value = """
            select t.trip_type as trip_type,
                   count(*) as trips,
                   coalesce(sum(t.seats_total), 0) as seats_published,
                   coalesce(sum(bk.seats_booked), 0) as seats_booked,
                   count(*) filter (where coalesce(bk.seats_booked, 0) = 0) as orphan_trips
            from trips t
            left join lateral (
                select sum(b.seats) as seats_booked
                from bookings b
                where b.trip_id = t.id and b.status in (:soldStatuses)
            ) bk on true
            where t.departure_at >= :from and t.departure_at < :to
              and t.status not in ('DRAFT', 'CANCELLED')
            group by t.trip_type
            order by t.trip_type
            """, nativeQuery = true)
    List<ModeFillStats> getFillStatsByMode(@Param("from") Instant from, @Param("to") Instant to,
                                           @Param("soldStatuses") List<String> soldStatuses);

    /**
     * Meme calcul que {@link #getFillStatsByMode}, par axe (libelles exacts
     * d'origine/destination, meme convention que AdminStatsService#topRoutes) et par
     * mode, classes par places publiees decroissantes.
     */
    @Query(value = """
            select t.origin_label as origin,
                   t.dest_label as destination,
                   t.trip_type as trip_type,
                   count(*) as trips,
                   coalesce(sum(t.seats_total), 0) as seats_published,
                   coalesce(sum(bk.seats_booked), 0) as seats_booked,
                   count(*) filter (where coalesce(bk.seats_booked, 0) = 0) as orphan_trips
            from trips t
            left join lateral (
                select sum(b.seats) as seats_booked
                from bookings b
                where b.trip_id = t.id and b.status in (:soldStatuses)
            ) bk on true
            where t.departure_at >= :from and t.departure_at < :to
              and t.status not in ('DRAFT', 'CANCELLED')
            group by t.origin_label, t.dest_label, t.trip_type
            order by seats_published desc, trips desc, origin, destination
            limit :limit
            """, nativeQuery = true)
    List<RouteFillStats> getFillStatsByRoute(@Param("from") Instant from, @Param("to") Instant to,
                                             @Param("soldStatuses") List<String> soldStatuses,
                                             @Param("limit") int limit);

    /**
     * Delai median (heures) entre la publication d'un trajet cree sur [from, to) et sa
     * premiere reservation vendue (statuts donnes), sur les seuls trajets ayant recu au
     * moins une reservation ({@code sampleSize}). Un trajet jamais reserve ne contribue
     * pas : c'est le taux de trajets orphelins qui en rend compte, pas ce delai.
     */
    @Query(value = """
            select percentile_cont(0.5) within group (
                     order by extract(epoch from (fb.first_booking_at - t.created_at)) / 3600.0) as median_hours,
                   count(*) as sample_size
            from trips t
            join lateral (
                select min(b.created_at) as first_booking_at
                from bookings b
                where b.trip_id = t.id and b.status in (:soldStatuses)
            ) fb on fb.first_booking_at is not null
            where t.created_at >= :from and t.created_at < :to
            """, nativeQuery = true)
    FirstBookingDelayStats getFirstBookingDelayStats(@Param("from") Instant from, @Param("to") Instant to,
                                                     @Param("soldStatuses") List<String> soldStatuses);

    // ------------------------------------------------------------------
    // KPI de retention (AdminRetentionService, point n.14) : cohortes calculees
    // en SQL, jamais de conducteurs ni de trajets charges en memoire.
    // ------------------------------------------------------------------

    /** Cohorte de conducteurs et republications, pour {@link #getDriverRetentionStats}. */
    interface DriverRetentionStats {
        long getW1Cohort();

        long getW1Retained();

        long getW4Cohort();

        long getW4Retained();
    }

    /**
     * Retention conducteur (constat F449, CLAUDE.md section 2). Une publication est un trajet
     * cree par son conducteur (hors brouillon et hors occurrence engendree par une navette :
     * {@code parent_trip_id is null}). La cohorte est l ensemble des conducteurs ayant publie
     * sur [from, to), ancres a leur premiere publication de la periode ({@code first_at}) :
     * <ul>
     *   <li>W1 : a republie entre J+1 et J+7 apres cette ancre ;</li>
     *   <li>W4 : a republie entre J+22 et J+28.</li>
     * </ul>
     * Un conducteur n entre dans le denominateur d une fenetre que si elle est entierement
     * ecoulee a {@code now} : un conducteur arrive hier n est pas "perdu", il est inobservable.
     */
    @Query(value = """
            with pubs as (
                select t.driver_id, t.created_at
                from trips t
                where t.status <> 'DRAFT' and t.parent_trip_id is null
            ),
            cohort as (
                select p.driver_id, min(p.created_at) as first_at
                from pubs p
                where p.created_at >= :from and p.created_at < :to
                group by p.driver_id
            )
            select
              count(*) filter (where c.first_at + interval '7 days' <= cast(:now as timestamptz)) as w1_cohort,
              count(*) filter (where c.first_at + interval '7 days' <= cast(:now as timestamptz)
                                 and exists (select 1 from pubs p where p.driver_id = c.driver_id
                                             and p.created_at > c.first_at
                                             and p.created_at <= c.first_at + interval '7 days')) as w1_retained,
              count(*) filter (where c.first_at + interval '28 days' <= cast(:now as timestamptz)) as w4_cohort,
              count(*) filter (where c.first_at + interval '28 days' <= cast(:now as timestamptz)
                                 and exists (select 1 from pubs p where p.driver_id = c.driver_id
                                             and p.created_at > c.first_at + interval '21 days'
                                             and p.created_at <= c.first_at + interval '28 days')) as w4_retained
            from cohort c
            """, nativeQuery = true)
    DriverRetentionStats getDriverRetentionStats(@Param("from") Instant from, @Param("to") Instant to,
                                                 @Param("now") Instant now);

    /** Navettes actives et remplissage moyen des occurrences, pour {@link #getRecurringStats}. */
    interface RecurringStats {
        long getActiveTemplates();

        long getOccurrences();

        Double getAvgFilledSeats();
    }

    /**
     * Mode quotidien (CLAUDE.md section 2) : navettes (modeles) ayant au moins une occurrence
     * partie sur [from, to), nombre de ces occurrences et places vendues (statuts donnes) en
     * moyenne par occurrence. Les occurrences annulees ou en brouillon ne comptent pas.
     */
    @Query(value = """
            select count(distinct t.parent_trip_id) as active_templates,
                   count(*) as occurrences,
                   cast(avg(coalesce(bk.seats_sold, 0)) as double precision) as avg_filled_seats
            from trips t
            left join lateral (
                select sum(b.seats) as seats_sold
                from bookings b
                where b.trip_id = t.id and b.status in (:soldStatuses)
            ) bk on true
            where t.parent_trip_id is not null
              and t.departure_at >= :from and t.departure_at < :to
              and t.status not in ('DRAFT', 'CANCELLED')
            """, nativeQuery = true)
    RecurringStats getRecurringStats(@Param("from") Instant from, @Param("to") Instant to,
                                     @Param("soldStatuses") List<String> soldStatuses);
}
