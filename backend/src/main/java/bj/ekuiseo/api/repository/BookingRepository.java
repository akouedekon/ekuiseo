package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByPassengerIdOrderByCreatedAtDesc(UUID passengerId);

    /** Reservations d un passager, paginees (fiche utilisateur du back-office, GET /api/v1/admin/users/{id}/bookings). */
    org.springframework.data.domain.Page<Booking> findByPassengerIdOrderByCreatedAtDesc(UUID passengerId,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Variante avec chargement anticipe du trajet, de son conducteur et de son
     * vehicule (JOIN FETCH sur des associations @ManyToOne, donc sans risque de
     * pagination en memoire) : evite le N+1 lors de la construction de
     * BookingDetailResponse (GET /api/v1/bookings?expand=trip,...).
     */
    @Query("select b from Booking b join fetch b.trip t join fetch t.driver join fetch t.vehicle "
            + "where b.passenger.id = :passengerId order by b.createdAt desc")
    List<Booking> findByPassengerIdWithTripFetched(@Param("passengerId") UUID passengerId);

    long countByPassengerId(UUID passengerId);

    List<Booking> findByTripIdAndStatusIn(UUID tripId, List<BookingStatus> statuses);

    boolean existsByTripIdAndPassengerIdAndStatusIn(UUID tripId, UUID passengerId, List<BookingStatus> statuses);

    /** Reservation du passager sur ce trajet dans l un des statuts donnes, la plus recente d abord (lien signalant/cible, constat F548). */
    List<Booking> findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(UUID tripId, UUID passengerId, List<BookingStatus> statuses);

    /**
     * Reservations qui lient deux utilisateurs dans un sens ou dans l autre (a passager d un
     * trajet conduit par b, ou l inverse), les plus recentes d abord. Preuve d une
     * interaction reelle avant un signalement NO_SHOW / HARASSMENT / ... (constat F548).
     */
    @Query("select b from Booking b join b.trip t where b.status in :statuses "
            + "and ((b.passenger.id = :a and t.driver.id = :b) or (b.passenger.id = :b and t.driver.id = :a)) "
            + "order by b.createdAt desc")
    List<Booking> findSharedBookings(@Param("a") UUID a, @Param("b") UUID b, @Param("statuses") List<BookingStatus> statuses);

    /** Reservations encore engageantes d un passager (anonymisation, constat F507). */
    boolean existsByPassengerIdAndStatusIn(UUID passengerId, List<BookingStatus> statuses);

    /** Reservations en attente dont l echeance d acompte (expires_at, V12) est depassee. */
    /** Reservations confirmees de trajets partis depuis longtemps : a cloturer COMPLETED (TripLifecycleScheduler). */
    @Query("select b from Booking b join fetch b.trip t where b.status = :status and t.departureAt < :before "
            + "and t.status <> bj.ekuiseo.api.domain.enums.TripStatus.CANCELLED")
    List<Booking> findByStatusWithTripDepartedBefore(@Param("status") BookingStatus status, @Param("before") Instant before);

    /** Reservations actives d un passager (cascade de suspension). */
    List<Booking> findByPassengerIdAndStatusIn(UUID passengerId, List<BookingStatus> statuses);

    @Query("select b from Booking b where b.status = :status and b.expiresAt is not null and b.expiresAt < :now")
    List<Booking> findExpirable(@Param("status") BookingStatus status, @Param("now") Instant now);

    /**
     * Reservations encaissees (au moins en partie) par la plateforme via Kkiapay -
     * MOMO_DEPOSIT ou MOMO_FULL, jamais CASH - pour un conducteur donne, dans l'un
     * des statuts donnes, et pas encore incluses dans un lot de reversement (regle
     * metier n.12, voir PayoutService#netAmount pour le calcul du montant net qui
     * differe entre les deux modes MoMo).
     */
    @Query("select b from Booking b where b.trip.driver.id = :driverId and b.status in :statuses "
            + "and b.paymentMethod in :methods and b.trip.departureAt < :cutoff "
            + "and exists (select p from Payment p where p.booking = b and p.status = bj.ekuiseo.api.domain.enums.PaymentStatus.SUCCEEDED) "
            + "and b.id not in (select i.bookingId from DriverPayoutItem i)")
    List<Booking> findPayableForDriver(@Param("driverId") UUID driverId,
                                        @Param("statuses") List<BookingStatus> statuses,
                                        @Param("methods") List<PaymentMethod> methods,
                                        @Param("cutoff") Instant cutoff);

    /** Identifiants distincts des conducteurs ayant au moins une reservation reversable. */
    @Query("select distinct b.trip.driver.id from Booking b where b.status in :statuses "
            + "and b.paymentMethod in :methods and b.trip.departureAt < :cutoff "
            + "and exists (select p from Payment p where p.booking = b and p.status = bj.ekuiseo.api.domain.enums.PaymentStatus.SUCCEEDED) "
            + "and b.id not in (select i.bookingId from DriverPayoutItem i)")
    List<UUID> findDriverIdsWithPayableBookings(@Param("statuses") List<BookingStatus> statuses,
                                                 @Param("methods") List<PaymentMethod> methods,
                                                 @Param("cutoff") Instant cutoff);

    long countByCreatedAtBetween(Instant from, Instant to);

    @Query("select coalesce(sum(b.amount), 0) from Booking b where b.createdAt between :from and :to "
            + "and b.status in :statuses")
    long sumAmountBetween(@Param("from") Instant from, @Param("to") Instant to,
                          @Param("statuses") List<BookingStatus> statuses);

    @Query("select coalesce(sum(b.serviceFee), 0) from Booking b where b.createdAt between :from and :to "
            + "and b.status in :statuses")
    long sumServiceFeeBetween(@Param("from") Instant from, @Param("to") Instant to,
                              @Param("statuses") List<BookingStatus> statuses);

    /** Utilisateurs distincts ayant reserve dans la periode, proxy de "utilisateurs actifs" (voir AdminStatsResponse.totals.activeUsers). */
    @Query("select count(distinct b.passenger.id) from Booking b where b.createdAt between :from and :to")
    long countDistinctPassengersBetween(@Param("from") Instant from, @Param("to") Instant to);

    // ------------------------------------------------------------------
    // Tableau de bord admin (AdminStatsService, constats F016/F238) : agregations
    // SQL natives sur [from, to), jamais le chargement des reservations en memoire.
    // Alias en snake_case (voir la note dans MessageRepository).
    // ------------------------------------------------------------------

    /** Totaux d une periode, pour {@link #getPeriodTotals}. */
    interface PeriodTotals {
        long getBookings();

        long getGmv();

        long getRevenue();
    }

    /** Reservations creees sur [from, to) ; GMV et commission sur les seuls statuts comptes (noms d enum BookingStatus). */
    @Query(value = """
            select count(*) as bookings,
                   coalesce(sum(case when b.status in (:counted) then b.amount else 0 end), 0) as gmv,
                   coalesce(sum(case when b.status in (:counted) then b.service_fee else 0 end), 0) as revenue
            from bookings b
            where b.created_at >= :from and b.created_at < :to
            """, nativeQuery = true)
    PeriodTotals getPeriodTotals(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("counted") List<String> counted);

    /** Serie journaliere, pour {@link #getDailyStats}. */
    interface DayStats {
        /** Jour civil du Benin, ISO AAAA-MM-JJ. */
        String getDay();

        long getBookings();

        long getGmv();

        long getRevenue();
    }

    @Query(value = """
            select to_char(date_trunc('day', b.created_at at time zone 'Africa/Porto-Novo'), 'YYYY-MM-DD') as day,
                   count(*) as bookings,
                   coalesce(sum(case when b.status in (:counted) then b.amount else 0 end), 0) as gmv,
                   coalesce(sum(case when b.status in (:counted) then b.service_fee else 0 end), 0) as revenue
            from bookings b
            where b.created_at >= :from and b.created_at < :to
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DayStats> getDailyStats(@Param("from") Instant from, @Param("to") Instant to,
                                 @Param("counted") List<String> counted);

    /** Repartition par statut, pour {@link #countByStatusBetween}. */
    interface StatusCount {
        String getStatus();

        long getCount();
    }

    @Query(value = """
            select b.status as status, count(*) as count
            from bookings b
            where b.created_at >= :from and b.created_at < :to
            group by b.status
            """, nativeQuery = true)
    List<StatusCount> countByStatusBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Axe le plus demande, pour {@link #getTopRoutes}. */
    interface RouteStats {
        String getOrigin();

        String getDestination();

        long getTrips();

        long getGmv();
    }

    /** Axes classes par GMV des reservations comptees creees sur [from, to) ; trips = trajets distincts reserves. */
    @Query(value = """
            select t.origin_label as origin,
                   t.dest_label as destination,
                   count(distinct t.id) as trips,
                   coalesce(sum(b.amount), 0) as gmv
            from bookings b
            join trips t on t.id = b.trip_id
            where b.created_at >= :from and b.created_at < :to
              and b.status in (:counted)
            group by t.origin_label, t.dest_label
            order by gmv desc, trips desc, origin, destination
            limit :limit
            """, nativeQuery = true)
    List<RouteStats> getTopRoutes(@Param("from") Instant from, @Param("to") Instant to,
                                  @Param("counted") List<String> counted, @Param("limit") int limit);

    /** Compte par identifiant, pour {@link #countByPassengerIds}. */
    interface IdCount {
        UUID getId();

        long getCount();
    }

    /** Reservations par passager, pour une liste d identifiants (back-office, plus de N+1 : constats F016/F308). */
    @Query("select b.passenger.id as id, count(b) as count from Booking b where b.passenger.id in :ids group by b.passenger.id")
    List<IdCount> countByPassengerIds(@Param("ids") List<UUID> ids);

    /** Places des reservations creees sur [from, to) dans les statuts donnes (metrique nord, voir AdminLiquidityService). */
    @Query("select coalesce(sum(b.seats), 0L) from Booking b where b.createdAt >= :from and b.createdAt < :to "
            + "and b.status in :statuses")
    long sumSeatsBetween(@Param("from") Instant from, @Param("to") Instant to,
                         @Param("statuses") List<BookingStatus> statuses);

    /** Places par semaine civile, pour {@link #getSeatsByWeek}. */
    interface WeekSeats {
        /** Lundi de la semaine, au format ISO AAAA-MM-JJ (formate en SQL pour ne pas dependre du type Java d'une date native). */
        String getWeekStart();

        long getSeats();
    }

    /**
     * Places reservees par semaine civile (lundi) de creation de la reservation, sur
     * [from, to) et dans les statuts donnes (noms d'enum BookingStatus). Trajectoire de
     * la metrique nord "places confirmees par semaine" (AdminLiquidityService). Une
     * seule requete agregee, jamais le chargement des reservations en memoire.
     */
    @Query(value = """
            select to_char(date_trunc('week', b.created_at), 'YYYY-MM-DD') as week_start,
                   coalesce(sum(b.seats), 0) as seats
            from bookings b
            where b.created_at >= :from and b.created_at < :to
              and b.status in (:statuses)
            group by 1
            order by 1
            """, nativeQuery = true)
    List<WeekSeats> getSeatsByWeek(@Param("from") Instant from, @Param("to") Instant to,
                                   @Param("statuses") List<String> statuses);

    /** Compte brut, une seule requete, pour {@link #getReliabilityStats}. */
    interface DriverReliabilityStats {
        long getCompleted();

        long getNoShow();

        long getLateCancelledByDriver();
    }

    /**
     * Statistiques "fiabilite" d'un conducteur (profil public, GET /api/v1/users/{id},
     * regle metier n.22) : une seule requete d'agregation, jamais le chargement d'une
     * collection de reservations en memoire - ce profil est consulte frequemment.
     * La "tardivite" d'une annulation conducteur n'est stockee nulle part par booking
     * (seul {@code users.late_cancellations_count} existe, un compteur cumule non
     * rattachable a une reservation precise) : elle est donc reconstruite ici depuis
     * {@code bookings.updated_at} (horodatage de l'annulation en cascade, voir
     * BookingService#cascadeCancelForDriverTripCancellation) compare a
     * {@code trips.departure_at - 24h}, exactement le seuil de DriverCancellationPolicy.
     * Indexee par idx_trips_driver (trips.driver_id) puis idx_bookings_trip
     * (bookings.trip_id), deja presents depuis V1 - aucun index supplementaire requis.
     */
    @Query(value = """
            select
              coalesce(sum(case when b.status = 'COMPLETED' then 1 else 0 end), 0) as completed,
              coalesce(sum(case when b.status = 'NO_SHOW' then 1 else 0 end), 0) as no_show,
              coalesce(sum(case when b.status = 'CANCELLED_BY_DRIVER'
                                  and b.updated_at >= (t.departure_at - interval '24 hours')
                            then 1 else 0 end), 0) as late_cancelled_by_driver
            from bookings b
            join trips t on t.id = b.trip_id
            where t.driver_id = :driverId
            """, nativeQuery = true)
    // Alias en snake_case (jamais camelCase) : Postgres replie tout alias non
    // quote en minuscules, et le mapping releche de Spring Data vers une
    // projection par interface se base sur cette convention underscore -> camelCase
    // pour retrouver getNoShow()/getLateCancelledByDriver() sans ambiguite.
    DriverReliabilityStats getReliabilityStats(@Param("driverId") UUID driverId);

    // ------------------------------------------------------------------
    // KPI de retention et de paiement (AdminRetentionService, point n.14) :
    // agregation SQL sur [from, to), jamais de reservations en memoire.
    // ------------------------------------------------------------------

    /** Cohorte de passagers, pour {@link #getPassengerRetentionStats}. */
    interface PassengerRetentionStats {
        long getCohortSize();

        long getRetained();
    }

    /**
     * Retention passager a 30 jours (CLAUDE.md section 2) : passagers ayant une reservation
     * vendue (statuts donnes) sur [from, to), ancres a leur premiere de la periode ; retenus
     * s ils en font une autre dans les 30 jours suivants. Seuls ceux dont la fenetre de 30
     * jours est ecoulee a {@code now} entrent dans le denominateur.
     */
    @Query(value = """
            with sold as (
                select b.passenger_id, b.created_at
                from bookings b
                where b.status in (:soldStatuses)
            ),
            cohort as (
                select s.passenger_id, min(s.created_at) as first_at
                from sold s
                where s.created_at >= :from and s.created_at < :to
                group by s.passenger_id
            )
            select
              count(*) filter (where c.first_at + interval '30 days' <= cast(:now as timestamptz)) as cohort_size,
              count(*) filter (where c.first_at + interval '30 days' <= cast(:now as timestamptz)
                                 and exists (select 1 from sold s where s.passenger_id = c.passenger_id
                                             and s.created_at > c.first_at
                                             and s.created_at <= c.first_at + interval '30 days')) as retained
            from cohort c
            """, nativeQuery = true)
    PassengerRetentionStats getPassengerRetentionStats(@Param("from") Instant from, @Param("to") Instant to,
                                                       @Param("soldStatuses") List<String> soldStatuses,
                                                       @Param("now") Instant now);

    /** Reservations vendues d une periode : part du quotidien, panier, places (voir {@link #getSoldBookingStats}). */
    interface SoldBookingStats {
        long getSoldBookings();

        long getDailyBookings();

        Double getAverageBasket();

        Double getSeatsPerBooking();
    }

    /** Reservations vendues (statuts donnes) creees sur [from, to) : total, part QUOTIDIEN, panier moyen, places par reservation. */
    @Query(value = """
            select count(*) as sold_bookings,
                   count(*) filter (where t.trip_type = 'QUOTIDIEN') as daily_bookings,
                   cast(avg(b.amount) as double precision) as average_basket,
                   cast(avg(b.seats) as double precision) as seats_per_booking
            from bookings b
            join trips t on t.id = b.trip_id
            where b.created_at >= :from and b.created_at < :to
              and b.status in (:soldStatuses)
            """, nativeQuery = true)
    SoldBookingStats getSoldBookingStats(@Param("from") Instant from, @Param("to") Instant to,
                                         @Param("soldStatuses") List<String> soldStatuses);

    /** Conversion reservation -> acompte encaisse et expirations, pour {@link #getMomoConversionStats}. */
    interface MomoConversionStats {
        long getMomoBookings();

        long getPaidBookings();

        long getExpiredBookings();
    }

    /**
     * Reservations mobile money (MOMO_DEPOSIT / MOMO_FULL, jamais CASH) creees sur [from, to) :
     * celles dont l acompte a ete encaisse (un paiement SUCCEEDED, rembourse ou en cours de
     * remboursement existe) et celles expirees faute de paiement dans les 20 minutes.
     */
    @Query(value = """
            select count(*) as momo_bookings,
                   count(*) filter (where exists (
                       select 1 from payments p
                       where p.booking_id = b.id
                         and p.status in ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_MANUAL'))) as paid_bookings,
                   count(*) filter (where b.status = 'EXPIRED') as expired_bookings
            from bookings b
            where b.created_at >= :from and b.created_at < :to
              and b.payment_method in ('MOMO_DEPOSIT', 'MOMO_FULL')
            """, nativeQuery = true)
    MomoConversionStats getMomoConversionStats(@Param("from") Instant from, @Param("to") Instant to);

    /** Repartition par mode de paiement, pour {@link #getPaymentMethodShare}. */
    interface PaymentMethodStats {
        String getMethod();

        long getCount();

        long getAmountFcfa();
    }

    /** Reservations vendues (statuts donnes) creees sur [from, to) par mode de paiement : nombre et volume (FCFA). */
    @Query(value = """
            select b.payment_method as method,
                   count(*) as count,
                   coalesce(sum(b.amount), 0) as amount_fcfa
            from bookings b
            where b.created_at >= :from and b.created_at < :to
              and b.status in (:soldStatuses)
            group by b.payment_method
            order by b.payment_method
            """, nativeQuery = true)
    List<PaymentMethodStats> getPaymentMethodShare(@Param("from") Instant from, @Param("to") Instant to,
                                                   @Param("soldStatuses") List<String> soldStatuses);
}
