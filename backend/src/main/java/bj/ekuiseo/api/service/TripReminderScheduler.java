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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Envoie le rappel "la veille du depart" (regle metier n.10). Execute chaque
 * heure ; recherche les trajets PUBLISHED ou FULL (constat F108) dont le depart tombe
 * entre 23h et 25h dans le futur (fenetre de 2h alignee sur la cadence horaire de ce
 * scheduler, pour ne jamais rater un trajet entre deux executions) et qui n'ont pas
 * deja recu leur rappel (trips.reminder_sent_at, marque une fois envoye pour ne jamais
 * doubler).
 *
 * <p>Constat F128 : une transaction par trajet (TransactionTemplate) et non une seule pour
 * la fournee, avec {@code reminder_sent_at} pose AVANT l envoi et de facon conditionnelle
 * ({@code where reminder_sent_at is null}) : un envoi lent ou en echec n empeche pas les
 * autres trajets d etre rappeles, un second passage (ou une seconde instance) ne double
 * jamais un SMS. Les envois sortants partent apres validation de la transaction du trajet
 * (NotificationDispatcher).</p>
 */
@Component
public class TripReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripReminderScheduler.class);
    private static final DateTimeFormatter DEPARTURE_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.FRENCH).withZone(Tz.BENIN);

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transaction;

    public TripReminderScheduler(TripRepository tripRepository, BookingRepository bookingRepository,
                                  NotificationService notificationService, PlatformTransactionManager transactionManager) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 0 * * * *")
    public void sendDueReminders() {
        try {
            sendDueReminders(Instant.now());
        } catch (RuntimeException ex) {
            log.error("Rappels de depart : echec de l execution", ex);
        }
    }

    /** Traitement a un instant donne (separe pour les tests) ; renvoie le nombre de trajets rappeles. */
    public int sendDueReminders(Instant now) {
        Instant from = now.plus(23, ChronoUnit.HOURS);
        Instant to = now.plus(25, ChronoUnit.HOURS);
        List<Trip> due = tripRepository.findDueForReminder(from, to);
        int reminded = 0;
        for (Trip trip : due) {
            try {
                Boolean sent = transaction.execute(status -> remind(trip, now));
                if (Boolean.TRUE.equals(sent)) {
                    reminded++;
                }
            } catch (RuntimeException ex) {
                log.error("Rappel de depart impossible pour le trajet {}", trip.getId(), ex);
            }
        }
        if (reminded > 0) {
            log.info("Rappels de depart envoyes pour {} trajet(s)", reminded);
        }
        return reminded;
    }

    /** Dans la transaction du trajet : marque d abord, envoie ensuite ; false si un autre passage l a deja marque. */
    private boolean remind(Trip trip, Instant now) {
        if (tripRepository.markReminderSent(trip.getId(), now) == 0) {
            return false;
        }
        List<Booking> confirmed = bookingRepository.findByTripIdAndStatusIn(trip.getId(), List.of(BookingStatus.CONFIRMED));
        String when = DEPARTURE_FORMAT.format(trip.getDepartureAt());
        String route = trip.getOriginLabel() + " -> " + trip.getDestLabel();
        for (Booking booking : confirmed) {
            notificationService.notifyCritical(booking.getPassenger(), NotificationType.TRIP_REMINDER,
                    Map.of("tripId", trip.getId().toString(), "bookingId", booking.getId().toString(),
                            "route", route, "departureAt", trip.getDepartureAt().toString()),
                    "Ekuiseo : rappel, votre trajet " + route + " part demain (" + when + "). Bon voyage !");
        }
        return true;
    }
}
