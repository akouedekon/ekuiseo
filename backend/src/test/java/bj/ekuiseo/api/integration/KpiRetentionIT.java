package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.GeoPlaceKind;
import bj.ekuiseo.api.domain.enums.PaymentChannel;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.service.admin.AdminRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Requetes natives des KPI de retention et de paiement (point n.14) executees sur PostGIS,
 * plus les objets de V17 (referentiel geo etendu, trigrammes, search_alert_matches, menage
 * des paiements abandonnes). Les dates sont relatives a l instant present : les chiffres
 * attendus ne dependent pas du jour.
 *
 * <p>Jeu de donnees (tout cree "maintenant", horodatages forces en SQL) :
 * <ul>
 *   <li>Conducteur A : publie il y a 40 j, republie il y a 36 j (W1) et il y a 14 j (W4) ;
 *       conducteur B : publie il y a 10 j, jamais republie ; conducteur C : publie il y a 3 j
 *       (fenetre W1 non ecoulee : inobservable).</li>
 *   <li>Passager 1 : reservation vendue il y a 40 j puis il y a 20 j (retenu) ; passager 2 :
 *       une seule il y a 35 j (perdu) ; passager 3 : il y a 5 j (inobservable).</li>
 *   <li>Une navette (TEMPLATE) avec deux occurrences parties (2 places vendues, puis 0).</li>
 *   <li>Reservations mobile money : 3 payees, 1 EXPIRED, 1 PENDING ; une CASH.</li>
 *   <li>Paiements : MTN 2 succes + 1 echec, MOOV 1 succes, un INITIATED abandonne.</li>
 * </ul></p>
 */
class KpiRetentionIT extends AbstractPostgisIT {

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private GeoPlaceRepository geoPlaceRepository;
    @Autowired
    private SearchAlertRepository searchAlertRepository;
    @Autowired
    private AdminRetentionService adminRetentionService;

    private User driverA;
    private Trip firstOfA;
    private Trip lastOfA;

    @BeforeEach
    void seed() {
        driverA = newUser("Awa", Role.USER);
        User driverB = newUser("Bio", Role.USER);
        User driverC = newUser("Chantal", Role.USER);
        Vehicle vehicleA = newVehicle(driverA);
        Vehicle vehicleB = newVehicle(driverB);
        Vehicle vehicleC = newVehicle(driverC);
        User passenger1 = newUser("Jean", Role.USER);
        User passenger2 = newUser("Fatou", Role.USER);
        User passenger3 = newUser("Koffi", Role.USER);

        // Publications (created_at force).
        firstOfA = publishedAt(newInterurbain(driverA, vehicleA, daysAgo(39)), 40);
        Trip aW1 = publishedAt(newInterurbain(driverA, vehicleA, daysAgo(35)), 36);
        lastOfA = publishedAt(newInterurbain(driverA, vehicleA, daysAgo(13)), 14);
        Trip bOnly = publishedAt(newInterurbain(driverB, vehicleB, daysAgo(9)), 10);
        publishedAt(newInterurbain(driverC, vehicleC, daysAgo(2)), 3);

        // Navette : modele + deux occurrences parties.
        Trip template = tripRepository.save(Trip.builder().driver(driverA).vehicle(vehicleA).tripType(TripType.QUOTIDIEN)
                .originLabel("Abomey-Calavi").originLat(CALAVI_LAT).originLng(CALAVI_LNG)
                .destLabel("Cotonou").destLat(COTONOU_LAT).destLng(COTONOU_LNG)
                .departureAt(daysAgo(8)).seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.TEMPLATE).recurrenceRule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR").build());
        Trip occurrence1 = tripRepository.save(occurrence(template, daysAgo(8)));
        tripRepository.save(occurrence(template, daysAgo(7)));

        // Reservations vendues (created_at force) : passagers 1, 2, 3.
        Booking p1First = bookingAt(booking(firstOfA, passenger1, 1, 5000, 400, BookingStatus.COMPLETED, PaymentMethod.MOMO_DEPOSIT), 40);
        Booking p1Second = bookingAt(booking(lastOfA, passenger1, 2, 10_000, 800, BookingStatus.COMPLETED, PaymentMethod.MOMO_DEPOSIT), 20);
        Booking p2Only = bookingAt(booking(aW1, passenger2, 1, 5000, 400, BookingStatus.COMPLETED, PaymentMethod.MOMO_FULL), 35);
        bookingAt(booking(bOnly, passenger3, 1, 5000, 0, BookingStatus.CONFIRMED, PaymentMethod.CASH), 5);
        // Occurrence 1 : 2 places vendues par le passager 3 ; occurrence 2 : rien.
        bookingAt(booking(occurrence1, passenger3, 2, 1000, 80, BookingStatus.COMPLETED, PaymentMethod.MOMO_DEPOSIT), 8);
        // Mobile money non aboutis.
        bookingAt(booking(bOnly, passenger2, 1, 5000, 400, BookingStatus.EXPIRED, PaymentMethod.MOMO_DEPOSIT), 9);
        bookingAt(booking(lastOfA, passenger2, 1, 5000, 400, BookingStatus.PENDING_PAYMENT, PaymentMethod.MOMO_DEPOSIT), 1);

        // Paiements.
        payment(p1First, "kk-1", PaymentStatus.SUCCEEDED, PaymentChannel.MTN, null, 40);
        payment(p1Second, "kk-2", PaymentStatus.SUCCEEDED, PaymentChannel.MTN, null, 20);
        payment(p2Only, "kk-3", PaymentStatus.SUCCEEDED, PaymentChannel.MOOV, null, 35);
        Booking failedBooking = bookingAt(booking(bOnly, passenger1, 1, 5000, 400, BookingStatus.PENDING_PAYMENT, PaymentMethod.MOMO_DEPOSIT), 9);
        payment(failedBooking, "kk-4", PaymentStatus.FAILED, PaymentChannel.MTN, null, 9);
        payment(failedBooking, "kk-5", PaymentStatus.FAILED, PaymentChannel.MTN, Map.of("decision", "ABANDONED"), 9);
        payment(failedBooking, "ekuiseo-booking-abandon", PaymentStatus.INITIATED, null, null, 1);
        // L acompte de la reservation de l occurrence : encaisse.
        bookingRepository.findAll().stream()
                .filter(b -> b.getTrip().getId().equals(occurrence1.getId()))
                .findFirst().ifPresent(b -> payment(b, "kk-6", PaymentStatus.SUCCEEDED, PaymentChannel.MOOV, null, 8));
    }

    @Test
    void driverRetention_countsObservableCohortsOnly() {
        Instant from = daysAgo(60);
        TripRepository_DriverStats stats = new TripRepository_DriverStats(tripRepository.getDriverRetentionStats(from, Instant.now(), Instant.now()));

        // A et B sont observables sur W1 (publies il y a 40 et 10 j) ; C (3 j) ne l est pas.
        assertThat(stats.w1Cohort).isEqualTo(2);
        assertThat(stats.w1Retained).as("A a republie a J+4").isEqualTo(1);
        // Seul A est observable sur W4 (28 j ecoules) ; il a republie a J+26.
        assertThat(stats.w4Cohort).isEqualTo(1);
        assertThat(stats.w4Retained).isEqualTo(1);
    }

    @Test
    void passengerRetention_thirtyDays() {
        var stats = bookingRepository.getPassengerRetentionStats(daysAgo(60), Instant.now(),
                List.of("CONFIRMED", "COMPLETED", "NO_SHOW"), Instant.now());

        // Passagers 1 et 2 observables (40 j et 35 j) ; passager 3 (8 j) ne l est pas.
        assertThat(stats.getCohortSize()).isEqualTo(2);
        assertThat(stats.getRetained()).as("seul le passager 1 a reserve de nouveau sous 30 j").isEqualTo(1);
    }

    @Test
    void soldBookings_dailyShare_basket_andSeats() {
        var stats = bookingRepository.getSoldBookingStats(daysAgo(60), Instant.now(), List.of("CONFIRMED", "COMPLETED", "NO_SHOW"));

        assertThat(stats.getSoldBookings()).isEqualTo(5);
        assertThat(stats.getDailyBookings()).as("la reservation sur l occurrence de navette").isEqualTo(1);
        assertThat(stats.getAverageBasket()).isCloseTo((5000 + 10_000 + 5000 + 5000 + 1000) / 5.0, within(0.01));
        assertThat(stats.getSeatsPerBooking()).isCloseTo((1 + 2 + 1 + 1 + 2) / 5.0, within(0.01));
    }

    @Test
    void recurringStats_countTemplatesAndAverageFilledSeats() {
        var stats = tripRepository.getRecurringStats(daysAgo(60), Instant.now(), List.of("CONFIRMED", "COMPLETED", "NO_SHOW"));

        assertThat(stats.getActiveTemplates()).isEqualTo(1);
        assertThat(stats.getOccurrences()).isEqualTo(2);
        assertThat(stats.getAvgFilledSeats()).isCloseTo(1.0, within(0.01)); // (2 + 0) / 2
    }

    @Test
    void momoConversion_andExpiredShare() {
        var stats = bookingRepository.getMomoConversionStats(daysAgo(60), Instant.now());

        // Mobile money : p1 x2, p2, occurrence, EXPIRED, PENDING, failedBooking = 7 ; CASH exclue.
        assertThat(stats.getMomoBookings()).isEqualTo(7);
        assertThat(stats.getPaidBookings()).as("un paiement SUCCEEDED existe").isEqualTo(4);
        assertThat(stats.getExpiredBookings()).isEqualTo(1);
    }

    @Test
    void kkiapayFailures_byRealOperator_ignoreAbandonedPayments() {
        List<PaymentRepository.OperatorFailureStats> byOperator = paymentRepository.getFailuresByOperator(daysAgo(60), Instant.now());

        assertThat(byOperator).extracting(PaymentRepository.OperatorFailureStats::getOperator)
                .containsExactly("MOOV", "MTN", "UNKNOWN");
        PaymentRepository.OperatorFailureStats mtn = byOperator.get(1);
        assertThat(mtn.getAttempts()).as("2 succes + 1 echec, l abandon exclu").isEqualTo(3);
        assertThat(mtn.getFailures()).isEqualTo(1);
        PaymentRepository.OperatorFailureStats unknown = byOperator.get(2);
        assertThat(unknown.getAttempts()).as("INITIATED n est pas une tentative").isZero();
    }

    @Test
    void paymentMethodShare_onSoldBookings() {
        var share = bookingRepository.getPaymentMethodShare(daysAgo(60), Instant.now(), List.of("CONFIRMED", "COMPLETED", "NO_SHOW"));

        assertThat(share).extracting(BookingRepository.PaymentMethodStats::getMethod)
                .containsExactly("CASH", "MOMO_DEPOSIT", "MOMO_FULL");
        assertThat(share.get(0).getCount()).isEqualTo(1);
        assertThat(share.get(0).getAmountFcfa()).isEqualTo(5000);
        assertThat(share.get(1).getCount()).isEqualTo(3);
        assertThat(share.get(1).getAmountFcfa()).isEqualTo(16_000);
    }

    @Test
    void adminRetentionService_assemblesEverything_andExportsCsv() {
        AdminRetentionResponse r = adminRetentionService.compute(60);

        assertThat(r.driverRetentionW1()).isEqualTo(0.5);
        assertThat(r.driverRetentionW4()).isEqualTo(1.0);
        assertThat(r.passengerRetention30d()).isEqualTo(0.5);
        assertThat(r.dailyModeShare()).isEqualTo(0.2);
        assertThat(r.activeRecurringTemplates()).isEqualTo(1);
        assertThat(r.avgFilledSeatsPerOccurrence()).isEqualTo(1.0);
        assertThat(r.bookingToDepositRate()).isCloseTo(4.0 / 7.0, within(0.001));
        assertThat(r.expiredBookingShare()).isCloseTo(1.0 / 7.0, within(0.001));
        assertThat(r.kkiapayFailureByOperator()).hasSize(3);
        assertThat(r.paymentMethodShare()).hasSize(3);
        assertThat(r.previous().driverRetentionW1()).as("rien avant J-60").isNull();

        String csv = adminRetentionService.toCsv(r);
        assertThat(csv).startsWith("﻿").contains("retention_conducteur_w1;0,5;").contains("MTN;3;1;");
    }

    @Test
    void housekeeping_failsAbandonedInitiatedPayments_onlyWithoutPendingBooking() {
        // Le paiement INITIATED de failedBooking porte la reference interne ; sa reservation est
        // encore PENDING_PAYMENT : rien ne se passe. Une fois la reservation expiree, il passe FAILED.
        assertThat(transactionTemplate.execute(tx -> paymentRepository.failAbandonedInitiated(Instant.now()))).isZero();
        jdbcTemplate.update("update bookings set status = 'EXPIRED' where status = 'PENDING_PAYMENT'");

        assertThat(transactionTemplate.execute(tx -> paymentRepository.failAbandonedInitiated(Instant.now()))).isEqualTo(1);

        Payment abandoned = paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, "ekuiseo-booking-abandon").orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(abandoned.getRawPayload()).containsEntry("decision", "ABANDONED");
        assertThat(abandoned.getUpdatedAt()).isNotNull();
    }

    @Test
    void geoPlaces_v17_stations_aliases_andTrigramFallback() {
        assertThat(geoPlaceRepository.search("jonquet", 5)).extracting(GeoPlace::getKind).containsExactly(GeoPlaceKind.STATION);
        assertThat(geoPlaceRepository.search("calavi", 5)).extracting(GeoPlace::getName)
                .as("alias de Abomey-Calavi, plus les lieux qui contiennent 'calavi'")
                .contains("Abomey-Calavi");
        assertThat(geoPlaceRepository.search("seme", 5)).extracting(GeoPlace::getName).contains("Sèmè-Kpodji");
        assertThat(geoPlaceRepository.search("natitngou", 5)).isEmpty();
        assertThat(geoPlaceRepository.searchFuzzy("natitngou", 5)).extracting(GeoPlace::getName).contains("Natitingou");
        GeoPlace ouando = geoPlaceRepository.search("Gare d'Ouando", 5).get(0);
        assertThat(ouando.getParentPlaceId()).isNotNull();
        assertThat(geoPlaceRepository.findById(ouando.getParentPlaceId()).orElseThrow().getName()).isEqualTo("Porto-Novo");
        // Le referentiel complet est trie villes d abord.
        List<GeoPlace> all = geoPlaceRepository.findAllByOrderByKindAscNameAsc();
        assertThat(all.size()).isGreaterThan(60);
        assertThat(all.get(0).getKind()).isEqualTo(GeoPlaceKind.CITY);
        // Rattachement des recherches a 15 km : Allada n est plus fondue dans Calavi.
        assertThat(geoPlaceRepository.findNearestCity(6.6653, 2.1514, 15_000)).get()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("Allada"));
    }

    @Test
    void searchAlertMatches_deduplicatePerAlertAndTripKey() {
        User passenger = newUser("Alerte", Role.USER);
        UUID alertId = jdbcTemplate.queryForObject("""
                insert into search_alerts (user_id, origin_label, origin_lat, origin_lng, dest_label, dest_lat, dest_lng, seats, active, radius_km)
                values (?, 'Cotonou', ?, ?, 'Bohicon', ?, ?, 1, true, 15) returning id
                """, UUID.class, passenger.getId(), COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG);
        UUID tripKey = firstOfA.getId();

        assertThat(transactionTemplate.execute(tx -> searchAlertRepository.insertMatch(alertId, tripKey))).isEqualTo(1);
        assertThat(transactionTemplate.execute(tx -> searchAlertRepository.insertMatch(alertId, tripKey))).as("deja prevenue").isZero();
        assertThat(transactionTemplate.execute(tx -> searchAlertRepository.insertMatch(alertId, lastOfA.getId()))).isEqualTo(1);
    }

    @Test
    void identityDocumentNumbers_areTruncatedByV17_onDecidedFiles() {
        User user = newUser("Piece", Role.USER);
        jdbcTemplate.update("insert into identity_verifications (user_id, document_type, document_number, status, submitted_at, reviewed_at) "
                + "values (?, 'CNI', 'B1234567', 'APPROVED', now(), now())", user.getId());
        // La migration est deja passee : on rejoue son UPDATE tel quel pour verifier sa forme.
        jdbcTemplate.update("update identity_verifications set document_number = '****' || right(document_number, 4) "
                + "where status in ('APPROVED', 'REJECTED') and document_number is not null and length(document_number) > 4 "
                + "and document_number not like '****%'");
        assertThat(jdbcTemplate.queryForObject("select document_number from identity_verifications where user_id = ?", String.class, user.getId()))
                .isEqualTo("****4567");
    }

    // ------------------------------------------------------------------ helpers

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private Trip newInterurbain(User driver, Vehicle vehicle, Instant departureAt) {
        Trip trip = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG, departureAt, 4, 5000);
        trip.setStatus(TripStatus.COMPLETED);
        return tripRepository.save(trip);
    }

    private static Trip occurrence(Trip template, Instant departureAt) {
        return Trip.builder().driver(template.getDriver()).vehicle(template.getVehicle()).tripType(TripType.QUOTIDIEN)
                .originLabel(template.getOriginLabel()).originLat(template.getOriginLat()).originLng(template.getOriginLng())
                .destLabel(template.getDestLabel()).destLat(template.getDestLat()).destLng(template.getDestLng())
                .departureAt(departureAt).seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.COMPLETED).parentTripId(template.getId()).build();
    }

    /** Force created_at (la colonne est non modifiable via JPA). */
    private Trip publishedAt(Trip trip, int daysAgo) {
        jdbcTemplate.update("update trips set created_at = now() - make_interval(days => ?) where id = ?", daysAgo, trip.getId());
        return trip;
    }

    private Booking bookingAt(Booking booking, int daysAgo) {
        Booking saved = bookingRepository.save(booking);
        jdbcTemplate.update("update bookings set created_at = now() - make_interval(days => ?) where id = ?", daysAgo, saved.getId());
        return saved;
    }

    private static Booking booking(Trip trip, User passenger, int seats, long amount, long fee, BookingStatus status, PaymentMethod method) {
        long deposit = method == PaymentMethod.CASH ? 0 : method == PaymentMethod.MOMO_FULL ? amount : Math.min(amount, Math.max(1000, fee));
        return Booking.builder()
                .trip(trip).passenger(passenger)
                .seats(seats).amount(amount).serviceFee(fee)
                .depositAmount(deposit).balanceDueOnBoard(amount - deposit)
                .paymentMethod(method)
                .status(status)
                .expiresAt(status == BookingStatus.PENDING_PAYMENT ? Instant.now().plus(20, ChronoUnit.MINUTES) : null)
                .build();
    }

    private void payment(Booking booking, String txId, PaymentStatus status, PaymentChannel channel,
                         Map<String, Object> rawPayload, int daysAgo) {
        Payment saved = paymentRepository.save(Payment.builder().booking(booking).provider(PaymentProvider.KKIAPAY)
                .providerTxId(txId).amount(booking.getDepositAmount()).status(status).channel(channel)
                .rawPayload(rawPayload).build());
        jdbcTemplate.update("update payments set created_at = now() - make_interval(days => ?) where id = ?", daysAgo, saved.getId());
    }

    /** Copie immuable de la projection (le proxy Spring Data n est pas lisible apres la transaction). */
    private record TripRepository_DriverStats(long w1Cohort, long w1Retained, long w4Cohort, long w4Retained) {
        TripRepository_DriverStats(bj.ekuiseo.api.repository.TripRepository.DriverRetentionStats s) {
            this(s.getW1Cohort(), s.getW1Retained(), s.getW4Cohort(), s.getW4Retained());
        }
    }
}
