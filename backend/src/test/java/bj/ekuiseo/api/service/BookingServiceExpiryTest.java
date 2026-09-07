package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F010/F116/F232 : une reservation impayee expire (EXPIRED), est journalisee sans acteur et notifiee au passager. */
class BookingServiceExpiryTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BookingService service = new BookingService(bookingRepository, tripRepository,
            mock(TripStopRepository.class), mock(UserRepository.class), mock(DriverSubscriptionRepository.class),
            mock(MessageRepository.class), mock(ReviewRepository.class), mock(BookingMapper.class),
            new CancellationPolicy(), new DriverCancellationPolicy(), notificationService, mock(PaymentService.class),
            auditService, new FeePolicy(0.08, 5, 1000), 20);

    @Test
    @SuppressWarnings("unchecked")
    void expireStalePendingBookings_setsExpired_releasesSeats_auditsAndNotifies() {
        User driver = User.builder().id(UUID.randomUUID()).build();
        User passenger = User.builder().id(UUID.randomUUID()).firstName("Awa").build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.FULL)
                .originLabel("Cotonou").destLabel("Bohicon").departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(4).seatsAvailable(0).build();
        Booking stale = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).seats(2).amount(5000)
                .status(BookingStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.MOMO_DEPOSIT)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)).build();
        when(bookingRepository.findExpirable(eq(BookingStatus.PENDING_PAYMENT), any())).thenReturn(List.of(stale));
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));

        int expired = service.expireStalePendingBookings();

        assertThat(expired).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(trip.getSeatsAvailable()).isEqualTo(2);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.PUBLISHED);
        verify(tripRepository).save(trip);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(isNull(), eq("BOOKING_EXPIRED"), eq("booking"), eq(stale.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("seatsReleased", 2).containsEntry("ttlMinutes", 20);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(passenger), eq(NotificationType.BOOKING_EXPIRED), payload.capture());
        assertThat(payload.getValue()).containsEntry("bookingId", stale.getId().toString()).containsEntry("route", "Cotonou -> Bohicon");
    }
}
