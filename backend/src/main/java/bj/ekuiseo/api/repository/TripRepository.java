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

    /** Trajets publies qui partent dans la fenetre [from, to) et n'ont pas encore recu leur rappel (regle metier n.10). */
    @Query("select t from Trip t where t.status = bj.ekuiseo.api.domain.enums.TripStatus.PUBLISHED "
            + "and t.reminderSentAt is null and t.departureAt between :from and :to")
    List<Trip> findDueForReminder(@Param("from") Instant from, @Param("to") Instant to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Trip t set t.reminderSentAt = :now where t.id = :id")
    int markReminderSent(@Param("id") UUID id, @Param("now") Instant now);

    long countByCreatedAtBetween(Instant from, Instant to);

    /** Horodatages de creation seuls (pas les entites completes) pour la serie temporelle du tableau de bord admin (voir AdminStatsService). */
    @Query("select t.createdAt from Trip t where t.createdAt between :from and :to")
    List<Instant> findCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Trajets PUBLISHED correspondant a une alerte de recherche (regle metier n.13) :
     * origine ET destination a moins de radiusMeters de l'alerte, et date de depart
     * dans la fenetre de l'alerte (bornes optionnelles). Utilisee par SearchAlertMatchService
     * juste apres la publication d'un trajet.
     */
    @Query(value = """
            select t.* from trips t
            where t.id = :tripId
              and ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
              and ST_DWithin(t.dest_point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
            """, nativeQuery = true)
    List<Trip> matchesAlertGeography(@Param("tripId") UUID tripId,
                                      @Param("originLat") double originLat, @Param("originLng") double originLng,
                                      @Param("destLat") double destLat, @Param("destLng") double destLng,
                                      @Param("radiusMeters") double radiusMeters);

    /**
     * Recherche geospatiale : trajets dont l'origine ET la destination sont a moins de
     * radiusMeters des points recherches (ST_DWithin sur les colonnes geography gerees
     * par trigger), tries par pertinence = distance cumulee + ecart horaire - bonus note
     * conducteur. Filtre optionnel sur la date (jour civil UTC) et le type de trajet.
     */
    @Query(value = """
            select t.* from trips t
            join users d on d.id = t.driver_id
            where t.status = 'PUBLISHED'
              and d.status = 'ACTIVE'
              and t.departure_at >= :now
              and t.seats_available >= :seats
              and (cast(:tripType as varchar) is null or t.trip_type = cast(:tripType as varchar))
              and (cast(:dateFrom as timestamptz) is null or t.departure_at >= cast(:dateFrom as timestamptz))
              and (cast(:dateTo as timestamptz) is null or t.departure_at < cast(:dateTo as timestamptz))
              and ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
              and ST_DWithin(t.dest_point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
            order by
              (ST_Distance(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography)
               + ST_Distance(t.dest_point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography))
              - (coalesce(d.rating_avg, 0) * 500)
              asc
            """,
            countQuery = """
            select count(*) from trips t
            join users d on d.id = t.driver_id
            where t.status = 'PUBLISHED'
              and d.status = 'ACTIVE'
              and t.departure_at >= :now
              and t.seats_available >= :seats
              and (cast(:tripType as varchar) is null or t.trip_type = cast(:tripType as varchar))
              and (cast(:dateFrom as timestamptz) is null or t.departure_at >= cast(:dateFrom as timestamptz))
              and (cast(:dateTo as timestamptz) is null or t.departure_at < cast(:dateTo as timestamptz))
              and ST_DWithin(t.origin_point, ST_SetSRID(ST_MakePoint(:originLng, :originLat), 4326)::geography, :radiusMeters)
              and ST_DWithin(t.dest_point, ST_SetSRID(ST_MakePoint(:destLng, :destLat), 4326)::geography, :radiusMeters)
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
                       Pageable pageable);

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
}
