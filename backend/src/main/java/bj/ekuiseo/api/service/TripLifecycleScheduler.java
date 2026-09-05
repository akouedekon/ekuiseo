package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cycle de vie automatique des trajets (constats F035/F101/F201/F209) :
 * <ul>
 *   <li>PUBLISHED / FULL dont le depart est passe -> ONGOING (plus de reservation, plus
 *       d annulation passager) ;</li>
 *   <li>ONGOING depuis plus de {@code ekuiseo.trip.completion-delay-hours} (6 h par
 *       defaut, au-dela de la duree de tout trajet interurbain du Benin) -> COMPLETED ;</li>
 *   <li>reservations CONFIRMED de ces trajets -> COMPLETED : elles deviennent
 *       reversables au conducteur (PayoutService) et comptent dans « trajets effectues »
 *       et la fiabilite. Un NO_SHOW pose par le conducteur avant la cloture est conserve.</li>
 * </ul>
 */
@Component
public class TripLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycleScheduler.class);
    private static final List<TripStatus> OPEN = List.of(TripStatus.PUBLISHED, TripStatus.FULL);

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final long completionDelayHours;

    public TripLifecycleScheduler(TripRepository tripRepository, BookingRepository bookingRepository,
                                  @Value("${ekuiseo.trip.completion-delay-hours:6}") long completionDelayHours) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.completionDelayHours = completionDelayHours;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    @Transactional
    public void run() {
        Result result = advance(Instant.now());
        if (result.started() > 0 || result.completed() > 0 || result.bookingsCompleted() > 0) {
            log.info("Cycle de vie : {} trajet(s) en cours, {} termine(s), {} reservation(s) cloturee(s)",
                    result.started(), result.completed(), result.bookingsCompleted());
        }
    }

    /** Applique les transitions dues a l instant {@code now}. Separe du scheduler pour les tests. */
    @Transactional
    public Result advance(Instant now) {
        int started = 0;
        for (Trip trip : tripRepository.findByStatusInAndDepartureAtBefore(OPEN, now)) {
            trip.setStatus(TripStatus.ONGOING);
            tripRepository.save(trip);
            started++;
        }
        Instant completionCutoff = now.minus(completionDelayHours, ChronoUnit.HOURS);
        int completed = 0;
        for (Trip trip : tripRepository.findByStatusInAndDepartureAtBefore(List.of(TripStatus.ONGOING), completionCutoff)) {
            trip.setStatus(TripStatus.COMPLETED);
            tripRepository.save(trip);
            completed++;
        }
        int bookingsCompleted = 0;
        for (Booking booking : bookingRepository.findByStatusWithTripDepartedBefore(BookingStatus.CONFIRMED, completionCutoff)) {
            booking.setStatus(BookingStatus.COMPLETED);
            booking.setExpiresAt(null);
            bookingRepository.save(booking);
            bookingsCompleted++;
        }
        return new Result(started, completed, bookingsCompleted);
    }

    public record Result(int started, int completed, int bookingsCompleted) {
    }
}
