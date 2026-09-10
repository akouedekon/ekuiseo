package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.enums.NoShowResolution;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V25 : les acomptes des absences non contestees sont rembourses un par un ; une erreur n arrete pas les autres. */
class BookingExpirySchedulerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private final BookingExpiryScheduler scheduler = new BookingExpiryScheduler(bookingService);

    @Test
    void refundUncontestedDriverNoShows_resolvesEachDueBooking_asTheSystem_andContinuesAfterAFailure() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(bookingService.findDriverNoShowRefundsDue(any(Instant.class))).thenReturn(List.of(failing, ok));
        when(bookingService.resolveDriverNoShow(isNull(), eq(failing), eq(NoShowResolution.REFUND_PASSENGER), isNull()))
                .thenThrow(new IllegalStateException("Kkiapay indisponible"));

        scheduler.refundUncontestedDriverNoShows();

        verify(bookingService).resolveDriverNoShow(isNull(), eq(failing), eq(NoShowResolution.REFUND_PASSENGER), isNull());
        verify(bookingService).resolveDriverNoShow(isNull(), eq(ok), eq(NoShowResolution.REFUND_PASSENGER), isNull());
    }
}
