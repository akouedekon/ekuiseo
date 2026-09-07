package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mise en forme des KPI de retention (point n.14) : fenetres de periode, fractions nulles
 * quand le denominateur est nul, arrondis et CSV. Les requetes natives elles-memes sont
 * couvertes par KpiRetentionIT sur PostGIS.
 */
class AdminRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final AdminRetentionService service = new AdminRetentionService(tripRepository, bookingRepository,
            paymentRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static TripRepository.DriverRetentionStats drivers(long w1Cohort, long w1, long w4Cohort, long w4) {
        return new TripRepository.DriverRetentionStats() {
            public long getW1Cohort() { return w1Cohort; }
            public long getW1Retained() { return w1; }
            public long getW4Cohort() { return w4Cohort; }
            public long getW4Retained() { return w4; }
        };
    }

    private static BookingRepository.PassengerRetentionStats passengers(long cohort, long retained) {
        return new BookingRepository.PassengerRetentionStats() {
            public long getCohortSize() { return cohort; }
            public long getRetained() { return retained; }
        };
    }

    private static BookingRepository.SoldBookingStats sold(long sold, long daily, Double basket, Double seats) {
        return new BookingRepository.SoldBookingStats() {
            public long getSoldBookings() { return sold; }
            public long getDailyBookings() { return daily; }
            public Double getAverageBasket() { return basket; }
            public Double getSeatsPerBooking() { return seats; }
        };
    }

    private static TripRepository.RecurringStats recurring(long templates, long occurrences, Double avg) {
        return new TripRepository.RecurringStats() {
            public long getActiveTemplates() { return templates; }
            public long getOccurrences() { return occurrences; }
            public Double getAvgFilledSeats() { return avg; }
        };
    }

    private static BookingRepository.MomoConversionStats momo(long momo, long paid, long expired) {
        return new BookingRepository.MomoConversionStats() {
            public long getMomoBookings() { return momo; }
            public long getPaidBookings() { return paid; }
            public long getExpiredBookings() { return expired; }
        };
    }

    private static PaymentRepository.OperatorFailureStats operator(String op, long attempts, long failures) {
        return new PaymentRepository.OperatorFailureStats() {
            public String getOperator() { return op; }
            public long getAttempts() { return attempts; }
            public long getFailures() { return failures; }
        };
    }

    private static BookingRepository.PaymentMethodStats method(String m, long count, long amount) {
        return new BookingRepository.PaymentMethodStats() {
            public String getMethod() { return m; }
            public long getCount() { return count; }
            public long getAmountFcfa() { return amount; }
        };
    }

    @Test
    void compute_usesTheRequestedWindow_andThePreviousOne_withFractions() {
        Instant from = NOW.minus(30, ChronoUnit.DAYS);
        Instant previousFrom = from.minus(30, ChronoUnit.DAYS);
        when(tripRepository.getDriverRetentionStats(from, NOW, NOW)).thenReturn(drivers(8, 3, 4, 1));
        when(tripRepository.getDriverRetentionStats(previousFrom, from, NOW)).thenReturn(drivers(5, 5, 5, 2));
        when(bookingRepository.getPassengerRetentionStats(eq(from), eq(NOW), anyList(), eq(NOW))).thenReturn(passengers(10, 4));
        when(bookingRepository.getPassengerRetentionStats(eq(previousFrom), eq(from), anyList(), eq(NOW))).thenReturn(passengers(0, 0));
        when(bookingRepository.getSoldBookingStats(eq(from), eq(NOW), anyList())).thenReturn(sold(20, 7, 4325.5, 1.35));
        when(bookingRepository.getSoldBookingStats(eq(previousFrom), eq(from), anyList())).thenReturn(sold(0, 0, null, null));
        when(tripRepository.getRecurringStats(eq(from), eq(NOW), anyList())).thenReturn(recurring(2, 12, 1.6667));
        when(tripRepository.getRecurringStats(eq(previousFrom), eq(from), anyList())).thenReturn(recurring(0, 0, null));
        when(bookingRepository.getMomoConversionStats(from, NOW)).thenReturn(momo(16, 12, 3));
        when(bookingRepository.getMomoConversionStats(previousFrom, from)).thenReturn(momo(0, 0, 0));
        when(paymentRepository.getFailuresByOperator(from, NOW)).thenReturn(List.of(operator("MTN", 10, 2), operator("UNKNOWN", 1, 0)));
        when(bookingRepository.getPaymentMethodShare(eq(from), eq(NOW), anyList()))
                .thenReturn(List.of(method("CASH", 3, 9000), method("MOMO_DEPOSIT", 17, 80_000)));

        AdminRetentionResponse r = service.compute(30);

        assertThat(r.days()).isEqualTo(30);
        assertThat(r.driverRetentionW1()).isEqualTo(0.375);
        assertThat(r.driverRetentionW4()).isEqualTo(0.25);
        assertThat(r.passengerRetention30d()).isEqualTo(0.4);
        assertThat(r.dailyModeShare()).isEqualTo(0.35);
        assertThat(r.activeRecurringTemplates()).isEqualTo(2);
        assertThat(r.avgFilledSeatsPerOccurrence()).isEqualTo(1.67);
        assertThat(r.bookingToDepositRate()).isEqualTo(0.75);
        assertThat(r.expiredBookingShare()).isEqualTo(0.1875);
        assertThat(r.averageBasketFcfa()).isEqualTo(4325.5);
        assertThat(r.seatsPerBooking()).isEqualTo(1.35);
        assertThat(r.kkiapayFailureByOperator()).extracting(AdminRetentionResponse.OperatorFailure::operator)
                .containsExactly("MTN", "UNKNOWN");
        assertThat(r.paymentMethodShare()).extracting(AdminRetentionResponse.PaymentMethodShare::method)
                .containsExactly(PaymentMethod.CASH, PaymentMethod.MOMO_DEPOSIT);

        // Periode precedente : denominateurs nuls -> null, jamais 0 ni NaN.
        AdminRetentionResponse.Scalars p = r.previous();
        assertThat(p.driverRetentionW1()).isEqualTo(1.0);
        assertThat(p.driverRetentionW4()).isEqualTo(0.4);
        assertThat(p.passengerRetention30d()).isNull();
        assertThat(p.dailyModeShare()).isNull();
        assertThat(p.activeRecurringTemplates()).isZero();
        assertThat(p.avgFilledSeatsPerOccurrence()).isNull();
        assertThat(p.bookingToDepositRate()).isNull();
        assertThat(p.expiredBookingShare()).isNull();
        assertThat(p.averageBasketFcfa()).isNull();
        assertThat(p.seatsPerBooking()).isNull();
    }

    @Test
    void compute_rejectsDaysOutOfRange() {
        assertThatThrownBy(() -> service.compute(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.compute(366)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void ratio_isNullOnZeroDenominator_andRoundedToFourDecimals() {
        assertThat(AdminRetentionService.ratio(1, 0)).isNull();
        assertThat(AdminRetentionService.ratio(1, 3)).isEqualTo(0.3333);
        assertThat(AdminRetentionService.ratio(0, 3)).isEqualTo(0.0);
        assertThat(AdminRetentionService.round(null, 2)).isNull();
        assertThat(AdminRetentionService.round(1.66666, 2)).isEqualTo(1.67);
    }

    @Test
    void toCsv_usesSemicolons_frenchDecimals_andABom() {
        when(tripRepository.getDriverRetentionStats(any(), any(), any())).thenReturn(drivers(8, 3, 0, 0));
        when(bookingRepository.getPassengerRetentionStats(any(), any(), anyList(), any())).thenReturn(passengers(10, 4));
        when(bookingRepository.getSoldBookingStats(any(), any(), anyList())).thenReturn(sold(20, 7, 4325.5, 1.35));
        when(tripRepository.getRecurringStats(any(), any(), anyList())).thenReturn(recurring(2, 12, 1.6667));
        when(bookingRepository.getMomoConversionStats(any(), any())).thenReturn(momo(16, 12, 3));
        when(paymentRepository.getFailuresByOperator(any(), any())).thenReturn(List.of(operator("MTN", 10, 2)));
        when(bookingRepository.getPaymentMethodShare(any(), any(), anyList())).thenReturn(List.of(method("CASH", 3, 9000)));

        String csv = service.toCsv(service.compute(7));

        assertThat(csv).startsWith("﻿")
                .contains("indicateur;periode_courante;periode_precedente\r\n")
                .contains("retention_conducteur_w1;0,375;0,375")
                .contains("retention_conducteur_w4;;")
                .contains("part_mode_quotidien;0,35;0,35")
                .contains("panier_moyen_fcfa;4325,5;4325,5")
                .contains("MTN;10;2;0,2")
                .contains("CASH;3;9000");
    }
}
