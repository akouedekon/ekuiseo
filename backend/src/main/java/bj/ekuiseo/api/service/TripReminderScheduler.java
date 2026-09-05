package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Envoie le rappel "la veille du depart" (regle metier n.10). Execute chaque
 * heure ; recherche les trajets PUBLISHED dont le depart tombe entre 23h et 25h
 * dans le futur (fenetre de 2h alignee sur la cadence horaire de ce scheduler, pour
 * ne jamais rater un trajet entre deux executions) et qui n'ont pas deja recu leur
 * rappel (trips.reminder_sent_at, marque une fois envoye pour ne jamais doubler).
 */
@Component
public class TripReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripReminderScheduler.class);
    private static final DateTimeFormatter DEPARTURE_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.FRENCH).withZone(Tz.BENIN);

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;

    public TripReminderScheduler(TripRepository tripRepository, BookingRepository bookingRepository,
                                  NotificationService notificationService) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void sendDueReminders() {
        Instant now = Instant.now();
        Instant from = now.plus(23, ChronoUnit.HOURS);
        Instant to = now.plus(25, ChronoUnit.HOURS);
        List<Trip> due = tripRepository.findDueForReminder(from, to);
        for (Trip trip : due) {
            List<Booking> confirmed = bookingRepository.findByTripIdAndStatusIn(trip.getId(), List.of(BookingStatus.CONFIRMED));
            String when = DEPARTURE_FORMAT.format(trip.getDepartureAt());
            for (Booking booking : confirmed) {
                notificationService.notifyCritical(booking.getPassenger(), NotificationType.TRIP_REMINDER,
                        Map.of("tripId", trip.getId().toString(), "bookingId", booking.getId().toString()),
                        "Ekuiseo : rappel, votre trajet " + trip.getOriginLabel() + " -> " + trip.getDestLabel()
                                + " part demain (" + when + "). Bon voyage !");
            }
            tripRepository.markReminderSent(trip.getId(), now);
        }
        if (!due.isEmpty()) {
            log.info("Rappels de depart envoyes pour {} trajet(s)", due.size());
        }
    }
}
