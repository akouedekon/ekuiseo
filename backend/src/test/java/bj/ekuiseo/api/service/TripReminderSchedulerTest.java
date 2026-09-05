package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F108 : un trajet FULL (voiture pleine) recoit le rappel J-1 comme un trajet PUBLISHED. */
class TripReminderSchedulerTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final TripReminderScheduler scheduler = new TripReminderScheduler(tripRepository, bookingRepository, notificationService);

    @Test
    void fullTrip_dueTomorrow_remindsEveryConfirmedPassenger_once() {
        Instant now = Instant.parse("2026-09-09T06:00:00Z");
        User driver = User.builder().id(UUID.randomUUID()).build();
        Trip full = Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.FULL)
                .originLabel("Abomey-Calavi").destLabel("Cotonou")
                .departureAt(now.plus(24, ChronoUnit.HOURS)).seatsTotal(3).seatsAvailable(0).build();
        User p1 = User.builder().id(UUID.randomUUID()).phone("+2290100000001").build();
        User p2 = User.builder().id(UUID.randomUUID()).phone("+2290100000002").build();
        Booking b1 = Booking.builder().id(UUID.randomUUID()).trip(full).passenger(p1).status(BookingStatus.CONFIRMED).build();
        Booking b2 = Booking.builder().id(UUID.randomUUID()).trip(full).passenger(p2).status(BookingStatus.CONFIRMED).build();

        when(tripRepository.findDueForReminder(now.plus(23, ChronoUnit.HOURS), now.plus(25, ChronoUnit.HOURS)))
                .thenReturn(List.of(full));
        when(bookingRepository.findByTripIdAndStatusIn(eq(full.getId()), eq(List.of(BookingStatus.CONFIRMED))))
                .thenReturn(List.of(b1, b2));

        int reminded = scheduler.sendDueReminders(now);

        assertThat(reminded).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(p1), eq(NotificationType.TRIP_REMINDER), payload.capture(), contains("demain"));
        verify(notificationService).notifyCritical(eq(p2), eq(NotificationType.TRIP_REMINDER), any(), contains("Abomey-Calavi -> Cotonou"));
        assertThat(payload.getValue()).containsEntry("route", "Abomey-Calavi -> Cotonou")
                .containsEntry("departureAt", full.getDepartureAt().toString());
        verify(tripRepository).markReminderSent(full.getId(), now);
    }

    @Test
    void nothingDue_sendsNothing() {
        when(tripRepository.findDueForReminder(any(), any())).thenReturn(List.of());

        assertThat(scheduler.sendDueReminders(Instant.now())).isZero();
        verify(notificationService, never()).notifyCritical(any(), any(), anyMap(), any());
        verify(tripRepository, never()).markReminderSent(any(), any());
    }
}
