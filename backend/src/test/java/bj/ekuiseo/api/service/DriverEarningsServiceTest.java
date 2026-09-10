package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.payout.DriverEarningsResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contrat A.8 : agregats en base, prochain lot lu dans le cron, compte mobile money verifie. */
class DriverEarningsServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
    private final DriverEarningsService service = new DriverEarningsService(bookingRepository, driverPayoutRepository,
            tripRepository, userRepository, paymentAccountRepository, 2000, 24, "0 0 6 * * MON");

    @Test
    void compute_assemblesTheAggregates() {
        UUID driverId = UUID.randomUUID();
        User driver = User.builder().id(driverId).ratingAvg(new BigDecimal("4.60")).ratingCount(12).build();
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(bookingRepository.getDriverEarnings(eq(driverId), any())).thenReturn(new BookingRepository.DriverEarningsStats() {
            @Override public long getBalance() { return 2600; }
            @Override public long getAwaiting() { return 760; }
            @Override public long getGross() { return 17_500; }
            @Override public long getCommission() { return 1_400; }
            @Override public long getCashCollected() { return 6_500; }
            @Override public long getSeatsSold() { return 7; }
        });
        when(driverPayoutRepository.sumAmountByDriverAndStatusIn(driverId, List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING))).thenReturn(1_840L);
        when(driverPayoutRepository.sumSettledByDriver(driverId)).thenReturn(9_000L);
        when(tripRepository.countByDriverIdAndStatus(driverId, TripStatus.COMPLETED)).thenReturn(5L);
        when(tripRepository.countByDriverIdAndStatusInAndDepartureAtAfter(eq(driverId), any(), any())).thenReturn(2L);
        when(paymentAccountRepository.findByUserIdAndIsDefaultTrue(driverId))
                .thenReturn(Optional.of(PaymentAccount.builder().verifiedAt(Instant.now()).build()));
        when(bookingRepository.getDriverMonthlyEarnings(eq(driverId), any(), any())).thenReturn(List.of(new BookingRepository.DriverMonthStats() {
            @Override public String getMonth() { return "2026-09"; }
            @Override public long getGross() { return 7_500; }
            @Override public long getCommission() { return 600; }
            @Override public long getCash() { return 6_500; }
            @Override public long getTrips() { return 1; }
        }));

        DriverEarningsResponse res = service.compute(driverId);

        assertThat(res.balanceFcfa()).isEqualTo(2600);
        assertThat(res.awaitingEligibilityFcfa()).isEqualTo(760);
        assertThat(res.inPayoutFcfa()).isEqualTo(1840);
        assertThat(res.paidOutFcfa()).isEqualTo(9000);
        assertThat(res.grossFcfa()).isEqualTo(17_500);
        assertThat(res.commissionFcfa()).isEqualTo(1_400);
        assertThat(res.cashCollectedFcfa()).isEqualTo(6_500);
        assertThat(res.tripsCompleted()).isEqualTo(5);
        assertThat(res.tripsUpcoming()).isEqualTo(2);
        assertThat(res.seatsSold()).isEqualTo(7);
        assertThat(res.ratingAvg()).isEqualByComparingTo("4.60");
        assertThat(res.minimumPayoutFcfa()).isEqualTo(2000);
        assertThat(res.hasVerifiedPayoutAccount()).isTrue();
        assertThat(res.byMonth()).hasSize(1);
        assertThat(res.byMonth().get(0).netFcfa()).isEqualTo(6_900);
        ZonedDateTime next = res.nextPayoutAt().atZone(Tz.BENIN);
        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(next.getHour()).isEqualTo(6);
        assertThat(res.nextPayoutAt()).isAfter(Instant.now());
    }

    @Test
    void nextPayoutAt_isTheNextMondayAtSix_beninTime() {
        // Lundi 7 septembre 2026, 05:30 Benin : ce lundi meme a 06:00.
        Instant mondayEarly = ZonedDateTime.of(2026, 9, 7, 5, 30, 0, 0, Tz.BENIN).toInstant();
        assertThat(service.nextPayoutAt(mondayEarly)).isEqualTo(ZonedDateTime.of(2026, 9, 7, 6, 0, 0, 0, Tz.BENIN).toInstant());
        // Lundi 7 septembre 2026, 06:00 pile : le lundi suivant.
        Instant mondaySix = ZonedDateTime.of(2026, 9, 7, 6, 0, 0, 0, Tz.BENIN).toInstant();
        assertThat(service.nextPayoutAt(mondaySix)).isEqualTo(ZonedDateTime.of(2026, 9, 14, 6, 0, 0, 0, Tz.BENIN).toInstant());
        assertThat(DriverEarningsService.dayOf("0 30 7 * * FRI")).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(DriverEarningsService.timeOf("0 30 7 * * FRI")).isEqualTo(java.time.LocalTime.of(7, 30));
        assertThat(DriverEarningsService.dayOf("n importe quoi")).isEqualTo(DayOfWeek.MONDAY);
    }
}
