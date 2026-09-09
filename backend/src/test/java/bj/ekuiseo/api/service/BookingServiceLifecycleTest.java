package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PassengerConfirmation;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle de vie cote reservation (lot 1.3) : plus de reservation ni d annulation sur un
 * trajet parti, conducteur suspendu non reservable, no-show du conducteur, fenetre
 * d annulation gratuite apres modification d horaire.
 */
class BookingServiceLifecycleTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final BookingService service = new BookingService(bookingRepository, tripRepository,
            mock(TripStopRepository.class), userRepository, mock(DriverSubscriptionRepository.class),
            mock(MessageRepository.class), mock(ReviewRepository.class), reportRepository, mock(BookingMapper.class),
            new CancellationPolicy(), new DriverCancellationPolicy(), notificationService, paymentService,
            auditService, new FeePolicy(0.08, 5, 1000), new DriverApprovalPolicy(24), 20);

    private final User driver = User.builder().id(UUID.randomUUID()).phone("+2290197000001").firstName("Koffi").status(UserStatus.ACTIVE).build();
    private final User passenger = User.builder().id(UUID.randomUUID()).phone("+2290197000002").firstName("Awa").status(UserStatus.ACTIVE).build();

    private Trip trip(TripStatus status, Instant departureAt) {
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).status(status).departureAt(departureAt)
                .originLabel("Cotonou").destLabel("Bohicon").seatsTotal(4).seatsAvailable(3).pricePerSeat(3000).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));
        return trip;
    }

    private Booking booking(Trip trip, BookingStatus status) {
        Booking booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).seats(1)
                .amount(3000).serviceFee(240).depositAmount(1000).balanceDueOnBoard(2000)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).status(status).build();
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void quote_refusesDepartedTrip_template_andSuspendedDriver() {
        Trip departed = trip(TripStatus.PUBLISHED, Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> service.quote(departed.getId(), passenger.getId(), new BookingQuoteRequest(1, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("deja parti");

        Trip template = trip(TripStatus.TEMPLATE, Instant.now().plus(1, ChronoUnit.DAYS));
        assertThatThrownBy(() -> service.quote(template.getId(), passenger.getId(), new BookingQuoteRequest(1, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("plus de reservations");

        Trip suspendedDriverTrip = trip(TripStatus.PUBLISHED, Instant.now().plus(1, ChronoUnit.DAYS));
        driver.setStatus(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> service.quote(suspendedDriverTrip.getId(), passenger.getId(), new BookingQuoteRequest(1, null, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("plus disponible");
    }

    @Test
    void cancelByPassenger_refusesOnceTheTripHasDeparted() {
        Booking booking = booking(trip(TripStatus.ONGOING, Instant.now().minus(10, ChronoUnit.MINUTES)), BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> service.cancelByPassenger(booking.getId(), passenger.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("deja parti");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(paymentService, never()).refundBooking(any(), anyLong(), any());
    }

    @Test
    void cancelByPassenger_lateCancellation_notifiesDriverBySms_andRefundsHalf() {
        Trip trip = trip(TripStatus.PUBLISHED, Instant.now().plus(3, ChronoUnit.HOURS));
        Booking booking = booking(trip, BookingStatus.CONFIRMED);
        when(paymentService.refundBooking(any(), anyLong(), any()))
                .thenReturn(new PaymentService.RefundOutcome(PaymentService.RefundOutcome.Status.REQUESTED, "ok"));
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        service.cancelByPassenger(booking.getId(), passenger.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_PASSENGER);
        verify(paymentService).refundBooking(booking, 500L, "ANNULATION_PASSAGER");
        verify(notificationService).notifyCritical(eq(driver), eq(NotificationType.BOOKING_CANCELLED), any(), any(String.class));
        verify(notificationService).notify(eq(passenger), eq(NotificationType.BOOKING_CANCELLED), any());
    }

    @Test
    void cancelByPassenger_isFree_insideTheWindowOpenedByAScheduleChange() {
        Trip trip = trip(TripStatus.PUBLISHED, Instant.now().plus(3, ChronoUnit.HOURS));
        Booking booking = booking(trip, BookingStatus.CONFIRMED);
        booking.setFreeCancellationUntil(Instant.now().plus(20, ChronoUnit.HOURS));
        when(paymentService.refundBooking(any(), anyLong(), any()))
                .thenReturn(new PaymentService.RefundOutcome(PaymentService.RefundOutcome.Status.REQUESTED, "ok"));

        service.cancelByPassenger(booking.getId(), passenger.getId());

        verify(paymentService).refundBooking(booking, 1000L, "ANNULATION_PASSAGER");
    }

    @Test
    void markNoShow_byDriver_afterDeparture_keepsDepositAndNotifiesPassenger() {
        Trip trip = trip(TripStatus.ONGOING, Instant.now().minus(30, ChronoUnit.MINUTES));
        Booking booking = booking(trip, BookingStatus.CONFIRMED);

        service.markNoShow(booking.getId(), driver.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
        verify(paymentService, never()).refundBooking(any(), anyLong(), any());
        verify(notificationService).notify(eq(passenger), eq(NotificationType.BOOKING_NO_SHOW), any());
        verify(auditService).log(eq(driver.getId()), eq("BOOKING_NO_SHOW"), eq("booking"), eq(booking.getId()), any());
    }

    @Test
    void markNoShow_isRefused_beforeDeparture_andAfter48h() {
        Booking early = booking(trip(TripStatus.PUBLISHED, Instant.now().plus(1, ChronoUnit.HOURS)), BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.markNoShow(early.getId(), driver.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("apres l heure de depart");

        Booking late = booking(trip(TripStatus.COMPLETED, Instant.now().minus(3, ChronoUnit.DAYS)), BookingStatus.COMPLETED);
        assertThatThrownBy(() -> service.markNoShow(late.getId(), driver.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("48 h");
    }

    /* ------------------------------------------------ Constat du passager (V21) */

    @Test
    void confirmTripDone_afterDeparture_recordsTheConfirmation_withoutTouchingTheStatus() {
        Trip trip = trip(TripStatus.COMPLETED, Instant.now().minus(7, ChronoUnit.HOURS));
        Booking booking = booking(trip, BookingStatus.COMPLETED);

        service.confirmTripDone(booking.getId(), passenger.getId());

        assertThat(booking.getPassengerConfirmation()).isEqualTo(PassengerConfirmation.TRIP_DONE);
        assertThat(booking.getPassengerConfirmedAt()).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(auditService).log(eq(passenger.getId()), eq("BOOKING_TRIP_CONFIRMED"), eq("booking"), eq(booking.getId()), any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void reportDriverNoShow_withinTheWindow_excludesTheBookingFromPayouts_opensAReport_andWarnsTheDriver() {
        Trip trip = trip(TripStatus.ONGOING, Instant.now().minus(2, ChronoUnit.HOURS));
        Booking booking = booking(trip, BookingStatus.CONFIRMED);
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.setId(UUID.randomUUID());
            return report;
        });

        service.reportDriverNoShow(booking.getId(), passenger.getId(), "  Attendu 40 minutes a la gare, personne.  ");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DRIVER_NO_SHOW);
        assertThat(booking.getPassengerConfirmation()).isEqualTo(PassengerConfirmation.DRIVER_NO_SHOW);
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getReporter()).isSameAs(passenger);
        assertThat(saved.getValue().getReportedTrip()).isSameAs(trip);
        assertThat(saved.getValue().getBookingId()).isEqualTo(booking.getId());
        assertThat(saved.getValue().getReasonCode()).isEqualTo("NO_SHOW");
        assertThat(saved.getValue().getDetails()).isEqualTo("Attendu 40 minutes a la gare, personne.");
        // Aucun remboursement automatique : la moderation tranche apres avoir entendu les deux parties.
        verify(paymentService, never()).refundBooking(any(), anyLong(), any());
        verify(notificationService).notifyCritical(eq(driver), eq(NotificationType.DRIVER_NO_SHOW_REPORTED), any());
        verify(auditService).log(eq(passenger.getId()), eq("BOOKING_DRIVER_NO_SHOW"), eq("booking"), eq(booking.getId()), any());
    }

    @Test
    void passengerConfirmation_isRefused_beforeDeparture_forSomeoneElse_twice_andAfter24hForANoShow() {
        Booking early = booking(trip(TripStatus.PUBLISHED, Instant.now().plus(1, ChronoUnit.HOURS)), BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.confirmTripDone(early.getId(), passenger.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("apres l heure de depart");

        Booking departed = booking(trip(TripStatus.ONGOING, Instant.now().minus(1, ChronoUnit.HOURS)), BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> service.confirmTripDone(departed.getId(), driver.getId()))
                .isInstanceOf(ForbiddenException.class);

        service.confirmTripDone(departed.getId(), passenger.getId());
        assertThatThrownBy(() -> service.reportDriverNoShow(departed.getId(), passenger.getId(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("deja");

        Booking late = booking(trip(TripStatus.COMPLETED, Instant.now().minus(30, ChronoUnit.HOURS)), BookingStatus.COMPLETED);
        assertThatThrownBy(() -> service.reportDriverNoShow(late.getId(), passenger.getId(), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("24 h");
        // La confirmation explicite, elle, reste possible apres 24 h (sans effet sur l argent).
        service.confirmTripDone(late.getId(), passenger.getId());
        assertThat(late.getPassengerConfirmation()).isEqualTo(PassengerConfirmation.TRIP_DONE);

        Booking cancelled = booking(trip(TripStatus.ONGOING, Instant.now().minus(1, ChronoUnit.HOURS)), BookingStatus.CANCELLED_BY_PASSENGER);
        assertThatThrownBy(() -> service.reportDriverNoShow(cancelled.getId(), passenger.getId(), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("confirmee ou terminee");
    }
}
