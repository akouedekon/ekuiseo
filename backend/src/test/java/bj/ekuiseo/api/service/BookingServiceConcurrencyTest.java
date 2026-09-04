package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifie la regle metier n.1 : quand deux passagers reservent simultanement
 * la derniere place d'un trajet, un seul doit reussir et l'autre doit recevoir
 * un ConflictException, sans jamais laisser seats_available devenir negatif.
 *
 * <p>Le comportement atomique de TripRepository#decrementSeatsIfAvailable (un
 * UPDATE ... WHERE seats_available >= :seats cote base reelle) est simule ici
 * par un mock dont la reponse effectue elle-meme la verification-et-decrement
 * de facon synchronisee, reproduisant la semantique de l'UPDATE conditionnel
 * SQL sans necessiter de base de donnees reelle.</p>
 */
class BookingServiceConcurrencyTest {

    @Test
    void twoSimultaneousBookingsOnLastSeat_onlyOneSucceeds() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID passenger1Id = UUID.randomUUID();
        UUID passenger2Id = UUID.randomUUID();

        User driver = User.builder().id(driverId).build();
        User passenger1 = User.builder().id(passenger1Id).build();
        User passenger2 = User.builder().id(passenger2Id).build();

        Trip trip = Trip.builder()
                .id(tripId)
                .driver(driver)
                .status(TripStatus.PUBLISHED)
                .pricePerSeat(1000)
                .seatsTotal(1)
                .seatsAvailable(1) // il ne reste qu'UNE place
                .departureAt(Instant.now().plus(3, ChronoUnit.DAYS))
                .build();

        TripRepository tripRepository = mock(TripRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        BookingMapper bookingMapper = mock(BookingMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        PaymentService paymentService = mock(PaymentService.class);
        AuditService auditService = mock(AuditService.class);
        FeePolicy feePolicy = new FeePolicy(0.08, 5, 1000);

        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(driverSubscriptionRepository.hasActiveSubscription(any(), any())).thenReturn(false);
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(eq(tripId), any(), anyList())).thenReturn(false);
        when(userRepository.findById(passenger1Id)).thenReturn(Optional.of(passenger1));
        when(userRepository.findById(passenger2Id)).thenReturn(Optional.of(passenger2));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(
                new BookingResponse(UUID.randomUUID(), tripId, null, 1, 1000, 100, null, PaymentMethod.MOMO_DEPOSIT, Instant.now()));

        // Simule l'UPDATE conditionnel atomique : ne decremente que si assez de places.
        when(tripRepository.decrementSeatsIfAvailable(eq(tripId), anyInt())).thenAnswer(inv -> {
            int seats = inv.getArgument(1);
            synchronized (trip) {
                if (trip.getSeatsAvailable() >= seats) {
                    trip.setSeatsAvailable(trip.getSeatsAvailable() - seats);
                    return 1;
                }
                return 0;
            }
        });

        BookingService bookingService = new BookingService(bookingRepository, tripRepository, mock(bj.ekuiseo.api.repository.TripStopRepository.class), userRepository,
                driverSubscriptionRepository, messageRepository, bookingMapper, new CancellationPolicy(), new DriverCancellationPolicy(),
                notificationService, paymentService, auditService, feePolicy, 20);

        CreateBookingRequest request = new CreateBookingRequest(1, null, null, PaymentMethod.MOMO_DEPOSIT);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Object> f1 = pool.submit(() -> attemptBooking(bookingService, tripId, passenger1Id, request, ready, start));
            Future<Object> f2 = pool.submit(() -> attemptBooking(bookingService, tripId, passenger2Id, request, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown(); // libere les deux threads en meme temps

            Object r1 = f1.get(5, TimeUnit.SECONDS);
            Object r2 = f2.get(5, TimeUnit.SECONDS);

            List<Object> results = List.of(r1, r2);
            long successes = results.stream().filter(r -> r instanceof BookingResponse).count();
            long conflicts = results.stream().filter(r -> r instanceof ConflictException).count();

            assertThat(successes).as("exactement une des deux reservations doit reussir").isEqualTo(1);
            assertThat(conflicts).as("l'autre doit echouer avec un conflit (plus de places)").isEqualTo(1);
            assertThat(trip.getSeatsAvailable()).as("les places disponibles ne doivent jamais devenir negatives").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object attemptBooking(BookingService service, UUID tripId, UUID passengerId,
                                          CreateBookingRequest request, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            return service.createBooking(tripId, passengerId, request);
        } catch (ConflictException ex) {
            return ex;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
