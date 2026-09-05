package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Transitions automatiques PUBLISHED/FULL -> ONGOING -> COMPLETED et cloture des reservations (lot 1.3). */
class TripLifecycleSchedulerTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final TripLifecycleScheduler scheduler = new TripLifecycleScheduler(tripRepository, bookingRepository, 6);

    @Test
    void advance_startsDepartedTrips_completesOldOnes_andClosesConfirmedBookings() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        Trip departed = Trip.builder().id(UUID.randomUUID()).status(TripStatus.FULL)
                .departureAt(now.minus(10, ChronoUnit.MINUTES)).build();
        Trip longGone = Trip.builder().id(UUID.randomUUID()).status(TripStatus.ONGOING)
                .departureAt(now.minus(7, ChronoUnit.HOURS)).build();
        Booking confirmed = Booking.builder().id(UUID.randomUUID()).trip(longGone).status(BookingStatus.CONFIRMED)
                .expiresAt(now).build();

        when(tripRepository.findByStatusInAndDepartureAtBefore(eq(List.of(TripStatus.PUBLISHED, TripStatus.FULL)), eq(now)))
                .thenReturn(List.of(departed));
        when(tripRepository.findByStatusInAndDepartureAtBefore(eq(List.of(TripStatus.ONGOING)), eq(now.minus(6, ChronoUnit.HOURS))))
                .thenReturn(List.of(longGone));
        when(bookingRepository.findByStatusWithTripDepartedBefore(eq(BookingStatus.CONFIRMED), eq(now.minus(6, ChronoUnit.HOURS))))
                .thenReturn(List.of(confirmed));

        TripLifecycleScheduler.Result result = scheduler.advance(now);

        assertThat(result).isEqualTo(new TripLifecycleScheduler.Result(1, 1, 1));
        assertThat(departed.getStatus()).isEqualTo(TripStatus.ONGOING);
        assertThat(longGone.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(confirmed.getExpiresAt()).isNull();
    }

    @Test
    void advance_isQuiet_whenNothingIsDue() {
        when(tripRepository.findByStatusInAndDepartureAtBefore(any(), any())).thenReturn(List.of());
        when(bookingRepository.findByStatusWithTripDepartedBefore(any(), any())).thenReturn(List.of());

        assertThat(scheduler.advance(Instant.now())).isEqualTo(new TripLifecycleScheduler.Result(0, 0, 0));
    }
}
