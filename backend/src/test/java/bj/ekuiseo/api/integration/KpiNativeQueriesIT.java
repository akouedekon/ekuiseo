package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.SearchEvent;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse;
import bj.ekuiseo.api.dto.admin.AdminStatsResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.SearchEventRepository;
import bj.ekuiseo.api.service.admin.AdminLiquidityService;
import bj.ekuiseo.api.service.admin.AdminStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque requete native de KPI (liquidite, metrique nord, statistiques, profil conducteur,
 * referentiel geo) executee sur PostGIS avec un petit jeu de donnees connu. Les fenetres
 * de periode sont relatives a l'instant present, donc les chiffres attendus ne dependent
 * pas de la date du jour : tout est cree "maintenant", les departs passes sont places
 * il y a un ou deux jours, le depart a venir dans trois jours.
 *
 * <p>Jeu de donnees :
 * <ul>
 *   <li>Trajet A (INTERURBAIN Cotonou -> Bohicon, parti il y a 2 jours, 4 places, COMPLETED) :
 *       une reservation COMPLETED de 2 places, une NO_SHOW de 1 place.</li>
 *   <li>Trajet B (QUOTIDIEN Abomey-Calavi -> Cotonou, parti hier, 3 places) : aucune
 *       reservation, donc orphelin.</li>
 *   <li>Trajet C (INTERURBAIN Cotonou -> Parakou, dans 3 jours, 4 places, PUBLISHED) :
 *       une reservation PENDING_PAYMENT (place non vendue).</li>
 *   <li>Trois recherches : Cotonou -> Bohicon avec resultats (passager 1), deux
 *       Cotonou -> Natitingou sans resultat (anonyme, puis passager 2).</li>
 * </ul>
 * Les recherches sont ecrites avant les reservations : l'attribution recherche ->
 * reservation (meme utilisateur, sous 24 h) compte alors les deux passagers.</p>
 */
class KpiNativeQueriesIT extends AbstractPostgisIT {

    private static final List<String> SOLD = List.of("CONFIRMED", "COMPLETED", "NO_SHOW");

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private SearchEventRepository searchEventRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private GeoPlaceRepository geoPlaceRepository;
    @Autowired
    private AdminLiquidityService adminLiquidityService;
    @Autowired
    private AdminStatsService adminStatsService;

    private User driver;
    private Trip tripA;
    private Trip tripC;

    @BeforeEach
    void seed() {
        driver = newUser("Awa", Role.USER);
        Vehicle vehicle = newVehicle(driver);
        User passenger1 = newUser("Jean", Role.USER);
        User passenger2 = newUser("Fatou", Role.USER);

        tripA = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                Instant.now().minus(2, ChronoUnit.DAYS), 4, 5000);
        tripA.setStatus(TripStatus.COMPLETED);
        tripA = tripRepository.save(tripA);
        newTrip(driver, vehicle, TripType.QUOTIDIEN,
                "Abomey-Calavi", CALAVI_LAT, CALAVI_LNG, "Cotonou", COTONOU_LAT, COTONOU_LNG,
                Instant.now().minus(1, ChronoUnit.DAYS), 3, 500);
        tripC = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Parakou", PARAKOU_LAT, PARAKOU_LNG,
                Instant.now().plus(3, ChronoUnit.DAYS), 4, 8000);

        searchEventRepository.save(searchEvent(passenger1, "Cotonou", COTONOU_LAT, COTONOU_LNG,
                "Bohicon", BOHICON_LAT, BOHICON_LNG, 2));
        searchEventRepository.save(searchEvent(null, "Cotonou", COTONOU_LAT, COTONOU_LNG,
                "Natitingou", NATITINGOU_LAT, NATITINGOU_LNG, 0));
        searchEventRepository.save(searchEvent(passenger2, "Cotonou", COTONOU_LAT, COTONOU_LNG,
                "Natitingou", NATITINGOU_LAT, NATITINGOU_LNG, 0));

        bookingRepository.save(booking(tripA, passenger1, 2, 10_000, 800, BookingStatus.COMPLETED));
        bookingRepository.save(booking(tripA, passenger2, 1, 5_000, 400, BookingStatus.NO_SHOW));
        bookingRepository.save(booking(tripC, passenger1, 1, 8_000, 640, BookingStatus.PENDING_PAYMENT));
    }

    @Test
    void seatsByWeek_sumsSoldSeatsOnly() {
        List<BookingRepository.WeekSeats> weeks = bookingRepository.getSeatsByWeek(
                Instant.now().minus(30, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS), SOLD);

        assertThat(weeks).isNotEmpty();
        assertThat(weeks).allSatisfy(w -> assertThat(w.getWeekStart()).matches("\\d{4}-\\d{2}-\\d{2}"));
        assertThat(weeks.stream().mapToLong(BookingRepository.WeekSeats::getSeats).sum()).isEqualTo(3);
    }

    @Test
    void funnelStats_countSearches_resultsAndAttributedBookings() {
        SearchEventRepository.FunnelStats funnel = searchEventRepository.getFunnelStats(
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(funnel.getTotal()).isEqualTo(3);
        assertThat(funnel.getWithResults()).isEqualTo(1);
        assertThat(funnel.getByUsers()).isEqualTo(2);
        assertThat(funnel.getConverted()).as("les deux passagers ont reserve apres leur recherche").isEqualTo(2);
    }

    @Test
    void shortageRoutes_listAxesSearchedWithoutResults() {
        List<SearchEventRepository.ShortageRoute> shortages = searchEventRepository.findShortageRoutes(
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS), 10);

        assertThat(shortages).hasSize(1);
        SearchEventRepository.ShortageRoute route = shortages.get(0);
        assertThat(route.getOrigin()).isEqualTo("Cotonou");
        assertThat(route.getDestination()).isEqualTo("Natitingou");
        assertThat(route.getSearches()).isEqualTo(2);
        assertThat(route.getWithoutResults()).isEqualTo(2);
        assertThat(route.getLastSearchedEpoch()).isNotNull().isGreaterThan(0);
    }

    @Test
    void fillStatsByMode_andByRoute_onDepartedTrips() {
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant to = Instant.now();

        List<bj.ekuiseo.api.repository.TripRepository.ModeFillStats> byMode =
                tripRepository.getFillStatsByMode(from, to, SOLD);
        assertThat(byMode).extracting(bj.ekuiseo.api.repository.TripRepository.ModeFillStats::getTripType)
                .containsExactly("INTERURBAIN", "QUOTIDIEN");
        var interurbain = byMode.get(0);
        assertThat(interurbain.getTrips()).isEqualTo(1);
        assertThat(interurbain.getSeatsPublished()).isEqualTo(4);
        assertThat(interurbain.getSeatsBooked()).isEqualTo(3);
        assertThat(interurbain.getOrphanTrips()).isZero();
        var quotidien = byMode.get(1);
        assertThat(quotidien.getTrips()).isEqualTo(1);
        assertThat(quotidien.getSeatsPublished()).isEqualTo(3);
        assertThat(quotidien.getSeatsBooked()).isZero();
        assertThat(quotidien.getOrphanTrips()).isEqualTo(1);

        List<bj.ekuiseo.api.repository.TripRepository.RouteFillStats> byRoute =
                tripRepository.getFillStatsByRoute(from, to, SOLD, 10);
        assertThat(byRoute).hasSize(2);
        assertThat(byRoute.get(0).getOrigin()).isEqualTo("Cotonou");
        assertThat(byRoute.get(0).getDestination()).isEqualTo("Bohicon");
        assertThat(byRoute.get(0).getSeatsBooked()).isEqualTo(3);
        assertThat(byRoute.get(1).getOrigin()).isEqualTo("Abomey-Calavi");
        assertThat(byRoute.get(1).getOrphanTrips()).isEqualTo(1);
    }

    @Test
    void firstBookingDelay_medianOverTripsThatSoldAtLeastOneSeat() {
        bj.ekuiseo.api.repository.TripRepository.FirstBookingDelayStats delay =
                tripRepository.getFirstBookingDelayStats(
                        Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS), SOLD);

        assertThat(delay.getSampleSize()).as("seul le trajet A a une place vendue").isEqualTo(1);
        assertThat(delay.getMedianHours()).isNotNull().isBetween(0.0, 1.0);
    }

    @Test
    void popularRoutes_listUpcomingPublishedTripsWithSeats() {
        List<bj.ekuiseo.api.repository.TripRepository.PopularRouteStats> popular =
                tripRepository.findPopularRoutes(Instant.now(), 5);

        assertThat(popular).hasSize(1);
        assertThat(popular.get(0).getOriginLabel()).isEqualTo("Cotonou");
        assertThat(popular.get(0).getDestLabel()).isEqualTo("Parakou");
        assertThat(popular.get(0).getTrips()).isEqualTo(1);
        assertThat(popular.get(0).getMinPrice()).isEqualTo(8000);
        assertThat(popular.get(0).getOriginLat()).isCloseTo(COTONOU_LAT, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void driverReliability_andResponseTime_aggregateWithoutLoadingRows() {
        BookingRepository.DriverReliabilityStats reliability = bookingRepository.getReliabilityStats(driver.getId());
        assertThat(reliability.getCompleted()).isEqualTo(1);
        assertThat(reliability.getNoShow()).isEqualTo(1);
        assertThat(reliability.getLateCancelledByDriver()).isZero();

        MessageRepository.DriverResponseTimeStats responseTime = messageRepository.getResponseTimeStats(
                driver.getId(), Instant.now().minus(90, ChronoUnit.DAYS));
        assertThat(responseTime.getSampleSize()).isZero();
    }

    @Test
    void geoPlaces_nearestCityAndAccentInsensitiveSearch() {
        assertThat(geoPlaceRepository.findNearestCity(COTONOU_LAT, COTONOU_LNG, 15_000))
                .isPresent()
                .get().satisfies(place -> assertThat(place.getName()).isEqualToIgnoringCase("Cotonou"));
        assertThat(geoPlaceRepository.findNearestCity(0.0, 0.0, 15_000)).isEmpty();
        assertThat(geoPlaceRepository.search("COTON", 5))
                .extracting(bj.ekuiseo.api.domain.GeoPlace::getName)
                .anyMatch(name -> name.equalsIgnoreCase("Cotonou"));
    }

    @Test
    void adminLiquidityService_computesHeadlineNorthStarAndCsv() {
        AdminLiquidityResponse liquidity = adminLiquidityService.compute(30);

        assertThat(liquidity.northStar().confirmedSeats()).isEqualTo(3);
        assertThat(liquidity.northStar().previousConfirmedSeats()).isZero();
        assertThat(liquidity.northStar().weekly().stream().mapToLong(AdminLiquidityResponse.WeekSeats::seats).sum())
                .isEqualTo(3);
        AdminLiquidityResponse.Headline current = liquidity.current();
        assertThat(current.searches()).isEqualTo(3);
        assertThat(current.searchesWithResults()).isEqualTo(1);
        assertThat(current.searchesByUsers()).isEqualTo(2);
        assertThat(current.searchesConverted()).isEqualTo(2);
        assertThat(current.trips()).isEqualTo(2);
        assertThat(current.seatsPublished()).isEqualTo(7);
        assertThat(current.seatsBooked()).isEqualTo(3);
        assertThat(current.orphanTrips()).isEqualTo(1);
        assertThat(current.firstBookingSampleSize()).isEqualTo(1);
        assertThat(liquidity.fillByMode()).hasSize(2);
        assertThat(liquidity.shortageRoutes()).hasSize(1);
        assertThat(liquidity.shortageRoutes().get(0).destination()).isEqualTo("Natitingou");

        String csv = adminLiquidityService.toCsv(liquidity);
        assertThat(csv).startsWith("\uFEFF").contains("places_confirmees;3;0").contains("Natitingou");
    }

    @Test
    void adminStatsService_computesTotalsAndBreakdowns() {
        AdminStatsResponse stats = adminStatsService.computeStats(7);

        assertThat(stats.series()).hasSize(7);
        assertThat(stats.totals().trips()).isEqualTo(3);
        assertThat(stats.totals().bookings()).isEqualTo(3);
        assertThat(stats.totals().gmv()).as("seules CONFIRMED/COMPLETED comptent").isEqualTo(10_000);
        assertThat(stats.totals().revenue()).isEqualTo(800);
        assertThat(stats.totals().activeUsers()).isEqualTo(2);
        assertThat(stats.totals().newUsers()).isEqualTo(3);
        assertThat(stats.bookingsByStatus()).extracting(AdminStatsResponse.StatusCount::status)
                .containsExactlyInAnyOrder(BookingStatus.PENDING_PAYMENT, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
        assertThat(stats.topRoutes()).hasSize(1);
        assertThat(stats.topRoutes().get(0).destination()).isEqualTo("Bohicon");
    }

    private static SearchEvent searchEvent(User user, String originLabel, double oLat, double oLng,
                                           String destLabel, double dLat, double dLng, int results) {
        return SearchEvent.builder()
                .userId(user == null ? null : user.getId())
                .originLabel(originLabel).originLat(oLat).originLng(oLng)
                .destLabel(destLabel).destLat(dLat).destLng(dLng)
                .seats(1).radiusKm(15).resultCount(results)
                .build();
    }

    private static Booking booking(Trip trip, User passenger, int seats, long amount, long fee, BookingStatus status) {
        long deposit = Math.max(1000, fee); // acompte = max(1000, frais de service), regle metier n.3
        return Booking.builder()
                .trip(trip).passenger(passenger)
                .seats(seats).amount(amount).serviceFee(fee)
                .depositAmount(deposit).balanceDueOnBoard(amount - deposit)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT)
                .status(status)
                .expiresAt(status == BookingStatus.PENDING_PAYMENT ? Instant.now().plus(20, ChronoUnit.MINUTES) : null)
                .build();
    }
}
