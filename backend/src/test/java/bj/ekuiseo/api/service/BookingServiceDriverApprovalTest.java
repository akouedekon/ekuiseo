package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.dto.booking.TripBookingResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation conducteur d une reservation (V19, point n.13 de l audit, constat F048) :
 * accord, refus avec remboursement integral, delai depasse, annulation passager gratuite,
 * demande en especes immediate.
 */
class BookingServiceDriverApprovalTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final DriverSubscriptionRepository subscriptions = mock(DriverSubscriptionRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BookingService service = new BookingService(bookingRepository, tripRepository,
            mock(TripStopRepository.class), userRepository, subscriptions,
            mock(MessageRepository.class), mock(ReviewRepository.class), mock(BookingMapper.class),
            new CancellationPolicy(), new DriverCancellationPolicy(), notificationService, paymentService,
            auditService, new FeePolicy(0.08, 5, 1000), new DriverApprovalPolicy(24), 20);

    private final User driver = User.builder().id(UUID.randomUUID()).firstName("Koffi").identityVerified(true)
            .status(UserStatus.ACTIVE).build();
    private final User passenger = User.builder().id(UUID.randomUUID()).firstName("Awa").status(UserStatus.ACTIVE).build();
    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.FULL).instantBooking(false)
                .departureAt(Instant.now().plus(3, ChronoUnit.DAYS)).originLabel("Cotonou").destLabel("Parakou")
                .seatsTotal(4).seatsAvailable(0).pricePerSeat(5000).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });
        when(paymentService.refundBooking(any(), anyLong(), anyString()))
                .thenReturn(new PaymentService.RefundOutcome(PaymentService.RefundOutcome.Status.REQUESTED, "ok"));
    }

    private Booking pending(long deposit) {
        Booking booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).seats(2)
                .amount(10_000).serviceFee(800).depositAmount(deposit).balanceDueOnBoard(10_000 - deposit)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).status(BookingStatus.PENDING_DRIVER_APPROVAL)
                .approvalDeadlineAt(Instant.now().plus(20, ChronoUnit.HOURS)).build();
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    @SuppressWarnings("unchecked")
    void accept_confirmsTheBooking_andNotifiesThePassengerCritically() {
        Booking booking = pending(1000);

        service.acceptByDriver(booking.getId(), driver.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getApprovalDeadlineAt()).isNull();
        // Les places etaient deja decrementees a la demande : rien ne bouge sur le trajet.
        assertThat(trip.getSeatsAvailable()).isZero();
        verify(paymentService, never()).refundBooking(any(), anyLong(), anyString());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.BOOKING_CONFIRMED), payload.capture(), anyString());
        assertThat(payload.getValue()).containsEntry("forPassenger", true).containsEntry("acceptedByDriver", true)
                .containsEntry("balanceDueOnBoardFcfa", 9000L);
        verify(auditService).log(eq(driver.getId()), eq("BOOKING_ACCEPTED_BY_DRIVER"), eq("booking"), eq(booking.getId()), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void decline_releasesSeats_refundsTheWholeDeposit_andNotifiesWithTheReason() {
        Booking booking = pending(1000);

        service.declineByDriver(booking.getId(), driver.getId(), "  Vehicule deja plein  ");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_DRIVER);
        assertThat(booking.getApprovalDeadlineAt()).isNull();
        assertThat(trip.getSeatsAvailable()).isEqualTo(2);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.PUBLISHED);
        // Remboursement INTEGRAL de l acompte, quel que soit le delai avant le depart.
        verify(paymentService).refundBooking(booking, 1000L, "REFUS_CONDUCTEUR");
        // Un refus n est pas une annulation tardive : rien n est compte au conducteur.
        verify(userRepository, never()).incrementLateCancellations(any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.BOOKING_DECLINED), payload.capture());
        assertThat(payload.getValue()).containsEntry("reason", "Vehicule deja plein").containsEntry("timedOut", false)
                .containsEntry("refundAmountFcfa", 1000L);
        verify(auditService).log(eq(driver.getId()), eq("BOOKING_DECLINED_BY_DRIVER"), eq("booking"), eq(booking.getId()), any());
    }

    @Test
    void decline_withoutReason_isAccepted_andReasonIsOmittedFromThePayload() {
        Booking booking = pending(1000);

        service.declineByDriver(booking.getId(), driver.getId(), null);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_DRIVER);
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.BOOKING_DECLINED),
                org.mockito.ArgumentMatchers.argThat(p -> !p.containsKey("reason")));
    }

    @Test
    void acceptAndDecline_areReservedToTheDriver_andToPendingRequests() {
        Booking booking = pending(1000);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.acceptByDriver(booking.getId(), stranger)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.declineByDriver(booking.getId(), stranger, null)).isInstanceOf(ForbiddenException.class);

        booking.setStatus(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.acceptByDriver(booking.getId(), driver.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("n attend pas");
        assertThatThrownBy(() -> service.declineByDriver(booking.getId(), driver.getId(), null))
                .isInstanceOf(BadRequestException.class);
        verify(paymentService, never()).refundBooking(any(), anyLong(), anyString());
    }

    @Test
    void accept_isRefused_onceTheTripHasDeparted() {
        Booking booking = pending(1000);
        trip.setDepartureAt(Instant.now().minus(5, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> service.acceptByDriver(booking.getId(), driver.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("parti");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_DRIVER_APPROVAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void expireStaleApprovals_treatsAnUnansweredRequestAsADecline() {
        Booking booking = pending(1000);
        booking.setApprovalDeadlineAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(bookingRepository.findExpirableApprovals(any())).thenReturn(List.of(booking));

        int count = service.expireStaleApprovals();

        assertThat(count).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_DRIVER);
        assertThat(trip.getSeatsAvailable()).isEqualTo(2);
        verify(paymentService).refundBooking(booking, 1000L, "DELAI_ACCORD_CONDUCTEUR");
        verify(auditService).log(isNull(), eq("BOOKING_APPROVAL_TIMED_OUT"), eq("booking"), eq(booking.getId()), any());
        ArgumentCaptor<Map<String, Object>> passengerPayload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.BOOKING_DECLINED), passengerPayload.capture());
        assertThat(passengerPayload.getValue()).containsEntry("timedOut", true).doesNotContainKey("reason");
        // Le conducteur apprend qu une demande lui a echappe.
        ArgumentCaptor<Map<String, Object>> driverPayload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(driver), eq(NotificationType.BOOKING_DECLINED), driverPayload.capture());
        assertThat(driverPayload.getValue()).containsEntry("forDriver", true).containsEntry("passengerName", "Awa");
    }

    @Test
    void cancelByPassenger_isFree_whileTheDriverHasNotAnswered() {
        Booking booking = pending(1000);
        // A moins de 24 h du depart, le bareme retiendrait 50 % : pas ici, le passager n est engage a rien.
        trip.setDepartureAt(Instant.now().plus(3, ChronoUnit.HOURS));

        service.cancelByPassenger(booking.getId(), passenger.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_PASSENGER);
        assertThat(booking.getApprovalDeadlineAt()).isNull();
        assertThat(trip.getSeatsAvailable()).isEqualTo(2);
        verify(paymentService).refundBooking(booking, 1000L, "ANNULATION_PASSAGER");
        // Le conducteur avait la demande sous les yeux : il est prevenu du retrait.
        verify(notificationService).notifyCritical(eq(driver), eq(NotificationType.BOOKING_CANCELLED), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBooking_inCash_onANonInstantTrip_awaitsTheDriver_withADeadline() {
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setSeatsAvailable(4);
        when(tripRepository.decrementSeatsIfAvailable(eq(trip.getId()), anyInt())).thenReturn(1);
        when(userRepository.findById(passenger.getId())).thenReturn(Optional.of(passenger));
        when(subscriptions.hasActiveSubscription(any(), any())).thenReturn(false);

        service.createBooking(trip.getId(), passenger.getId(), new CreateBookingRequest(1, null, null, PaymentMethod.CASH));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        Booking booking = saved.getValue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_DRIVER_APPROVAL);
        assertThat(booking.getExpiresAt()).isNull();
        Instant expectedDeadline = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(booking.getApprovalDeadlineAt()).isBetween(expectedDeadline.minusSeconds(5), expectedDeadline.plusSeconds(5));
        ArgumentCaptor<Map<String, Object>> driverPayload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(driver), eq(NotificationType.BOOKING_REQUESTED), driverPayload.capture());
        assertThat(driverPayload.getValue()).containsEntry("passengerName", "Awa").containsKey("approvalDeadlineAt");
        verify(notificationService).notify(eq(passenger), eq(NotificationType.BOOKING_REQUESTED),
                org.mockito.ArgumentMatchers.argThat(p -> Boolean.TRUE.equals(p.get("forPassenger"))));
        verify(notificationService, never()).notifyCritical(any(), eq(NotificationType.BOOKING_CONFIRMED), any(), any());
    }

    @Test
    void createBooking_inCash_onAnInstantTrip_isConfirmedAtOnce() {
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setSeatsAvailable(4);
        trip.setInstantBooking(true);
        when(tripRepository.decrementSeatsIfAvailable(eq(trip.getId()), anyInt())).thenReturn(1);
        when(userRepository.findById(passenger.getId())).thenReturn(Optional.of(passenger));

        service.createBooking(trip.getId(), passenger.getId(), new CreateBookingRequest(1, null, null, PaymentMethod.CASH));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getValue().getApprovalDeadlineAt()).isNull();
        verify(notificationService, never()).notifyCritical(any(), eq(NotificationType.BOOKING_REQUESTED), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForDriver_includesPendingRequests_withTheirDeadline() {
        Booking booking = pending(1000);
        when(bookingRepository.findByTripIdAndStatusIn(eq(trip.getId()), any())).thenReturn(List.of(booking));

        List<TripBookingResponse> rows = service.listForDriver(trip.getId(), driver.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(BookingStatus.PENDING_DRIVER_APPROVAL);
        assertThat(rows.get(0).approvalDeadlineAt()).isEqualTo(booking.getApprovalDeadlineAt());
        ArgumentCaptor<List<BookingStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(bookingRepository).findByTripIdAndStatusIn(eq(trip.getId()), statuses.capture());
        assertThat(statuses.getValue()).contains(BookingStatus.PENDING_DRIVER_APPROVAL, BookingStatus.CONFIRMED);
    }
}
