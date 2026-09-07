package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.config.AsyncConfig;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Fait vivre les alertes de recherche (regle metier n.13) : des qu un trajet est publie
 * (TripService#createTrip, RecurrenceService#generateFor), les alertes actives qui lui
 * correspondent sont trouvees en UNE requete PostGIS (SearchAlertRepository#findMatching,
 * constat F526 : fenetre de dates, places, type, rayon propre a l alerte, sens, arrets
 * intermediaires) et chacune donne lieu a une notification SEARCH_ALERT_MATCH - in-app, et
 * par e-mail via le routeur (NotificationService / NotificationDispatcher) avec un objet
 * explicite et un lien direct vers le trajet (constat F523).
 *
 * <p>Declenche par {@link TripPublishedEvent} apres validation de la transaction de
 * publication, sur l executeur dedie {@code alertExecutor} (constat F527) : une exception
 * ici est journalisee, jamais propagee a la publication ni a la generation nocturne.</p>
 */
@Service
public class SearchAlertMatchService {

    private static final Logger log = LoggerFactory.getLogger(SearchAlertMatchService.class);

    private final SearchAlertRepository searchAlertRepository;
    private final TripRepository tripRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public SearchAlertMatchService(SearchAlertRepository searchAlertRepository, TripRepository tripRepository,
                                    NotificationService notificationService, PlatformTransactionManager transactionManager) {
        this.searchAlertRepository = searchAlertRepository;
        this.tripRepository = tripRepository;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Apres commit de la publication, hors du fil de la requete : le trajet est recharge par
     * identifiant (l entite de la transaction d origine ne traverse pas les fils) dans une
     * transaction propre au fil asynchrone (TransactionTemplate : les associations paresseuses
     * - conducteur, utilisateur de l alerte - restent chargeables, et les notifications partent
     * apres son commit). Un trajet qui n est plus PUBLISHED entre-temps (annule, complet)
     * n alerte personne.
     */
    @Async(AsyncConfig.ALERT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripPublished(TripPublishedEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Trip trip = tripRepository.findById(event.tripId()).orElse(null);
                if (trip == null || trip.getStatus() != TripStatus.PUBLISHED) {
                    return;
                }
                notifyMatchingAlerts(trip);
            });
        } catch (RuntimeException ex) {
            log.error("Matching des alertes en echec pour le trajet {}", event.tripId(), ex);
        }
    }

    /** Une requete, une notification par alerte correspondante. Utilisable directement (tests, rattrapage). */
    @Transactional
    public int notifyMatchingAlerts(Trip trip) {
        LocalDate departureDate = trip.getDepartureAt().atZone(Tz.BENIN).toLocalDate();
        List<SearchAlert> matching = searchAlertRepository.findMatching(trip.getId(), trip.getDriver().getId(),
                departureDate, trip.getSeatsAvailable(), trip.getTripType() != null ? trip.getTripType().name() : null);
        for (SearchAlert alert : matching) {
            notificationService.notify(alert.getUser(), NotificationType.SEARCH_ALERT_MATCH,
                    NotificationTemplates.payload("tripId", trip.getId().toString(), "alertId", alert.getId().toString(),
                            "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                            "departureAt", Objects.toString(trip.getDepartureAt(), ""),
                            "pricePerSeatFcfa", trip.getPricePerSeat(),
                            "seatsAvailable", trip.getSeatsAvailable()));
        }
        if (!matching.isEmpty()) {
            log.info("Trajet {} : {} alerte(s) de recherche declenchee(s)", trip.getId(), matching.size());
        }
        return matching.size();
    }
}
