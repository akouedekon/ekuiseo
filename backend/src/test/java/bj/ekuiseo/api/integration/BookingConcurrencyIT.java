package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regle metier n.7 (places = ressource concurrente) sur une base reelle : deux
 * transactions simultanees sur la derniere place produisent exactement une confirmation
 * et un refus, jamais deux confirmations et jamais un nombre de places negatif. Le test
 * mocke de {@code BookingServiceConcurrencyTest} simule l'UPDATE conditionnel ; ici c'est
 * PostgreSQL qui arbitre (verrou de ligne + re-evaluation du WHERE apres commit).
 * Synchronisation par verrous (CountDownLatch), jamais par attente arbitraire.
 */
class BookingConcurrencyIT extends AbstractPostgisIT {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingRepository bookingRepository;

    private Trip lastSeatTrip;
    private User passenger1;
    private User passenger2;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        User driver = newUser("Awa", Role.USER);
        Vehicle vehicle = newVehicle(driver);
        passenger1 = newUser("Jean", Role.USER);
        passenger2 = newUser("Fatou", Role.USER);
        lastSeatTrip = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                Instant.now().plus(3, ChronoUnit.DAYS), 1, 2500);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void createBooking_twoConcurrentTransactionsOnLastSeat_exactlyOneSucceeds() throws Exception {
        CreateBookingRequest request = new CreateBookingRequest(1, null, null, PaymentMethod.MOMO_DEPOSIT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> f1 = pool.submit(() -> attempt(passenger1.getId(), request, ready, start));
        Future<Object> f2 = pool.submit(() -> attempt(passenger2.getId(), request, ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));

        assertThat(results.stream().filter(r -> r instanceof BookingResponse).count())
                .as("exactement une reservation doit aboutir").isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof ConflictException).count())
                .as("l autre doit recevoir un conflit (409)").isEqualTo(1);

        Trip refreshed = tripRepository.findById(lastSeatTrip.getId()).orElseThrow();
        assertThat(refreshed.getSeatsAvailable()).isZero();
        assertThat(refreshed.getStatus()).isEqualTo(TripStatus.FULL);
        assertThat(bookingRepository.findByTripIdAndStatusIn(lastSeatTrip.getId(),
                List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED))).hasSize(1);
    }

    /**
     * Au niveau de la requete elle-meme : la seconde transaction attend le verrou de ligne
     * pose par la premiere, puis re-evalue {@code seats_available >= :seats} sur la ligne
     * validee et ne modifie rien (0 ligne). La premiere transaction ne valide qu'une fois
     * la seconde engagee dans sa tentative.
     */
    @Test
    void decrementSeatsIfAvailable_secondTransactionSeesCommittedValue_andAffectsNoRow() throws Exception {
        UUID tripId = lastSeatTrip.getId();
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);

        Future<Integer> first = pool.submit(() -> transactionTemplate.execute(status -> {
            int affected = tripRepository.decrementSeatsIfAvailable(tripId, 1);
            firstHoldsLock.countDown();
            awaitQuietly(secondAttempting);
            return affected; // commit ici : la seconde transaction est liberee
        }));
        Future<Integer> second = pool.submit(() -> {
            awaitQuietly(firstHoldsLock);
            secondAttempting.countDown();
            return transactionTemplate.execute(status -> tripRepository.decrementSeatsIfAvailable(tripId, 1));
        });

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(second.get(30, TimeUnit.SECONDS)).isZero();
        assertThat(tripRepository.findById(tripId).orElseThrow().getSeatsAvailable()).isZero();
    }

    private Object attempt(UUID passengerId, CreateBookingRequest request, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        awaitQuietly(start);
        try {
            return bookingService.createBooking(lastSeatTrip.getId(), passengerId, request);
        } catch (ConflictException ex) {
            return ex;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synchronisation des threads de test expiree");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
