package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie la cascade d'annulation d'un trajet par son conducteur : toutes les
 * reservations actives sont annulees et remboursees integralement, les passagers
 * sont notifies, et l'annulation tardive est comptabilisee dans les statistiques
 * du conducteur (regle metier n.9).
 */
class BookingServiceDriverCancellationTest {

    @Test
    void lateCancellation_cancelsAllActiveBookings_refundsInFull_andPenalizesDriver() {
        UUID driverId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        User driver = User.builder().id(driverId).phone("+22997000001").lateCancellationsCount(0).build();
        Trip trip = Trip.builder()
                .id(tripId)
                .driver(driver)
                .status(TripStatus.CANCELLED)
                .departureAt(Instant.now().plus(2, ChronoUnit.HOURS)) // moins de 24h : tardif
                .build();

        User passenger1 = User.builder().id(UUID.randomUUID()).phone("+22997000002").build();
        User passenger2 = User.builder().id(UUID.randomUUID()).phone("+22997000003").build();
        // MOMO_FULL : depositAmount = amount (comportement historique "MOMO"), donc le
        // remboursement integral attendu plus bas (verify(...).refundBooking(booking, amount, ...))
        // reste 2000/4000 - le remboursement porte desormais sur depositAmount (regle n.21).
        Booking booking1 = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger1)
                .seats(1).amount(2000).serviceFee(160).depositAmount(2000).balanceDueOnBoard(0)
                .status(BookingStatus.CONFIRMED).paymentMethod(PaymentMethod.MOMO_FULL).build();
        Booking booking2 = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger2)
                .seats(2).amount(4000).serviceFee(320).depositAmount(4000).balanceDueOnBoard(0)
                .status(BookingStatus.CONFIRMED).paymentMethod(PaymentMethod.MOMO_FULL).build();

        BookingRepository bookingRepository = mock(BookingRepository.class);
        TripRepository tripRepository = mock(TripRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        BookingMapper bookingMapper = mock(BookingMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        PaymentService paymentService = mock(PaymentService.class);
        AuditService auditService = mock(AuditService.class);
        FeePolicy feePolicy = new FeePolicy(0.08, 5, 1000);

        when(bookingRepository.findByTripIdAndStatusIn(eq(tripId), any())).thenReturn(List.of(booking1, booking2));
        when(paymentService.refundBooking(any(Booking.class), anyLong(), any(String.class)))
                .thenReturn(new PaymentService.RefundOutcome(PaymentService.RefundOutcome.Status.SUCCEEDED, "ok"));

        BookingService bookingService = new BookingService(bookingRepository, tripRepository, userRepository,
                driverSubscriptionRepository, messageRepository, bookingMapper, new CancellationPolicy(), new DriverCancellationPolicy(),
                notificationService, paymentService, auditService, feePolicy, 20);

        bookingService.cascadeCancelForDriverTripCancellation(trip);

        assertThat(booking1.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_DRIVER);
        assertThat(booking2.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_DRIVER);
        verify(bookingRepository).save(booking1);
        verify(bookingRepository).save(booking2);

        // Remboursement integral de depositAmount (PAS amount-serviceFee, regle n.21) ;
        // ici depositAmount == amount car ces reservations sont MOMO_FULL.
        verify(paymentService).refundBooking(booking1, 2000L, "ANNULATION_CONDUCTEUR");
        verify(paymentService).refundBooking(booking2, 4000L, "ANNULATION_CONDUCTEUR");

        verify(notificationService, times(2)).notifyCritical(any(User.class), any(), any(), any(String.class));

        ArgumentCaptor<User> driverCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(driverCaptor.capture());
        assertThat(driverCaptor.getValue().getLateCancellationsCount()).isEqualTo(1);

        verify(auditService).log(eq(driverId), eq("TRIP_CANCELLED_BY_DRIVER"), eq("trip"), eq(tripId), any());
    }

    @Test
    void cancellationMoreThan24hBeforeDeparture_doesNotPenalizeDriver() {
        UUID driverId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        User driver = User.builder().id(driverId).phone("+22997000001").lateCancellationsCount(0).build();
        Trip trip = Trip.builder().id(tripId).driver(driver).status(TripStatus.CANCELLED)
                .departureAt(Instant.now().plus(72, ChronoUnit.HOURS)).build();

        BookingRepository bookingRepository = mock(BookingRepository.class);
        TripRepository tripRepository = mock(TripRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        BookingMapper bookingMapper = mock(BookingMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        PaymentService paymentService = mock(PaymentService.class);
        AuditService auditService = mock(AuditService.class);
        FeePolicy feePolicy = new FeePolicy(0.08, 5, 1000);

        when(bookingRepository.findByTripIdAndStatusIn(eq(tripId), any())).thenReturn(List.of());

        BookingService bookingService = new BookingService(bookingRepository, tripRepository, userRepository,
                driverSubscriptionRepository, messageRepository, bookingMapper, new CancellationPolicy(), new DriverCancellationPolicy(),
                notificationService, paymentService, auditService, feePolicy, 20);

        bookingService.cascadeCancelForDriverTripCancellation(trip);

        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }
}
