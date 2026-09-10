package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.payout.DriverEarningsResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

/**
 * Revenus du conducteur (contrat A.8) : cinq requetes agregees quelle que soit l activite du
 * conducteur (places vendues, lots en cours, lots regles, trajets termines, trajets a venir),
 * plus le detail des six derniers mois.
 */
@Service
public class DriverEarningsService {

    static final int MONTHS = 6;
    private static final List<PayoutStatus> IN_PAYOUT = List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING);
    private static final List<TripStatus> UPCOMING = List.of(TripStatus.PUBLISHED, TripStatus.FULL);

    private final BookingRepository bookingRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final long minimumThresholdFcfa;
    private final long eligibilityDelayHours;
    private final LocalTime payoutTime;
    private final DayOfWeek payoutDay;

    public DriverEarningsService(BookingRepository bookingRepository, DriverPayoutRepository driverPayoutRepository,
                                 TripRepository tripRepository, UserRepository userRepository,
                                 PaymentAccountRepository paymentAccountRepository,
                                 @Value("${ekuiseo.payout.minimum-threshold-fcfa:2000}") long minimumThresholdFcfa,
                                 @Value("${ekuiseo.payout.eligibility-delay-hours:24}") long eligibilityDelayHours,
                                 @Value("${ekuiseo.payout.auto-batch-cron:0 0 6 * * MON}") String payoutCron) {
        this.bookingRepository = bookingRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.minimumThresholdFcfa = minimumThresholdFcfa;
        this.eligibilityDelayHours = eligibilityDelayHours;
        this.payoutTime = timeOf(payoutCron);
        this.payoutDay = dayOf(payoutCron);
    }

    @Transactional(readOnly = true)
    public DriverEarningsResponse compute(UUID driverId) {
        User driver = userRepository.findById(driverId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        Instant now = Instant.now();
        Instant cutoff = now.minus(eligibilityDelayHours, ChronoUnit.HOURS);
        BookingRepository.DriverEarningsStats stats = bookingRepository.getDriverEarnings(driverId, cutoff);
        long inPayout = driverPayoutRepository.sumAmountByDriverAndStatusIn(driverId, IN_PAYOUT);
        long paidOut = driverPayoutRepository.sumSettledByDriver(driverId);
        long tripsCompleted = tripRepository.countByDriverIdAndStatus(driverId, TripStatus.COMPLETED);
        long tripsUpcoming = tripRepository.countByDriverIdAndStatusInAndDepartureAtAfter(driverId, UPCOMING, now);
        boolean verifiedAccount = paymentAccountRepository.findByUserIdAndIsDefaultTrue(driverId)
                .filter(a -> a.getVerifiedAt() != null).isPresent();

        YearMonth current = YearMonth.from(now.atZone(Tz.BENIN));
        Instant from = current.minusMonths(MONTHS - 1).atDay(1).atStartOfDay(Tz.BENIN).toInstant();
        Instant to = current.plusMonths(1).atDay(1).atStartOfDay(Tz.BENIN).toInstant();
        List<DriverEarningsResponse.Month> byMonth = bookingRepository.getDriverMonthlyEarnings(driverId, from, to).stream()
                .map(m -> new DriverEarningsResponse.Month(m.getMonth(), m.getGross(), m.getCommission(),
                        m.getGross() - m.getCommission(), m.getCash(), m.getTrips()))
                .toList();

        return new DriverEarningsResponse(
                stats == null ? 0 : stats.getBalance(),
                stats == null ? 0 : stats.getAwaiting(),
                inPayout, paidOut,
                stats == null ? 0 : stats.getGross(),
                stats == null ? 0 : stats.getCommission(),
                stats == null ? 0 : stats.getCashCollected(),
                tripsCompleted, tripsUpcoming,
                stats == null ? 0 : stats.getSeatsSold(),
                driver.getRatingCount() > 0 ? driver.getRatingAvg() : null, driver.getRatingCount(),
                minimumThresholdFcfa, nextPayoutAt(now), verifiedAccount, byMonth);
    }

    /** Prochaine constitution de lot (lundi 06:00 Benin par defaut, lue dans le cron du PayoutScheduler). */
    Instant nextPayoutAt(Instant now) {
        ZonedDateTime local = now.atZone(Tz.BENIN);
        ZonedDateTime candidate = local.with(TemporalAdjusters.nextOrSame(payoutDay)).with(payoutTime).withSecond(0).withNano(0);
        if (!candidate.isAfter(local)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
    }

    /** Heure du cron a 6 champs (« 0 0 6 * * MON ») ; 06:00 si illisible. */
    static LocalTime timeOf(String cron) {
        try {
            String[] parts = cron.trim().split("\\s+");
            return LocalTime.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]));
        } catch (RuntimeException ex) {
            return LocalTime.of(6, 0);
        }
    }

    /** Jour du cron (« MON », « 1 »...) ; lundi si illisible ou quotidien. */
    static DayOfWeek dayOf(String cron) {
        try {
            String day = cron.trim().split("\\s+")[5].toUpperCase();
            if (day.matches("\\d")) {
                int n = Integer.parseInt(day);
                return n == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(n);
            }
            return switch (day) {
                case "MON" -> DayOfWeek.MONDAY;
                case "TUE" -> DayOfWeek.TUESDAY;
                case "WED" -> DayOfWeek.WEDNESDAY;
                case "THU" -> DayOfWeek.THURSDAY;
                case "FRI" -> DayOfWeek.FRIDAY;
                case "SAT" -> DayOfWeek.SATURDAY;
                case "SUN" -> DayOfWeek.SUNDAY;
                default -> DayOfWeek.MONDAY;
            };
        } catch (RuntimeException ex) {
            return DayOfWeek.MONDAY;
        }
    }
}
