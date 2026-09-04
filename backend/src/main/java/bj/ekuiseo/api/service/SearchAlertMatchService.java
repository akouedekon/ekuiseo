package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * "Job" qui fait vivre les alertes de recherche (regle metier n.13) : des qu'un
 * trajet est publie (voir TripService#createTrip et RecurrenceService), on
 * recherche les alertes actives dont la fenetre de dates couvre ce depart, puis on
 * verifie la correspondance geographique fine (rayon autour de l'origine ET de la
 * destination de l'alerte) via la meme mecanique ST_DWithin que la recherche de
 * trajets. Chaque correspondance cree une notification pour l'utilisateur de
 * l'alerte.
 *
 * <p>Implemente comme un appel synchrone au moment de la publication plutot que
 * comme une tache planifiee separee : fonctionnellement equivalent (la notification
 * part des la publication, pas avec un delai de balayage) et plus simple. A migrer
 * vers un traitement asynchrone (event listener) si le volume de trajets publies
 * devient un probleme de latence a la creation.</p>
 */
@Service
public class SearchAlertMatchService {

    private static final Logger log = LoggerFactory.getLogger(SearchAlertMatchService.class);

    private final SearchAlertRepository searchAlertRepository;
    private final TripRepository tripRepository;
    private final NotificationService notificationService;
    private final double radiusMeters;

    public SearchAlertMatchService(SearchAlertRepository searchAlertRepository, TripRepository tripRepository,
                                    NotificationService notificationService,
                                    @Value("${ekuiseo.search-alert.radius-km:10}") double radiusKm) {
        this.searchAlertRepository = searchAlertRepository;
        this.tripRepository = tripRepository;
        this.notificationService = notificationService;
        this.radiusMeters = radiusKm * 1000.0;
    }

    @Transactional
    public void notifyMatchingAlerts(Trip trip) {
        LocalDate departureDate = trip.getDepartureAt().atZone(ZoneOffset.UTC).toLocalDate();
        List<SearchAlert> candidates = searchAlertRepository.findActiveCandidates(departureDate);
        int matches = 0;
        for (SearchAlert alert : candidates) {
            if (alert.getUser().getId().equals(trip.getDriver().getId())) {
                continue; // un conducteur n'est pas notifie de son propre trajet
            }
            if (trip.getSeatsAvailable() < alert.getSeats()) {
                continue; // pas assez de places pour satisfaire la demande de l'alerte (migration V6)
            }
            if (alert.getTripType() != null && alert.getTripType() != trip.getTripType()) {
                continue; // type de trajet demande (interurbain/quotidien) non respecte (migration V6)
            }
            List<Trip> geoMatch = tripRepository.matchesAlertGeography(trip.getId(),
                    alert.getOriginLat(), alert.getOriginLng(), alert.getDestLat(), alert.getDestLng(), radiusMeters);
            if (!geoMatch.isEmpty()) {
                notificationService.notify(alert.getUser(), NotificationType.SEARCH_ALERT_MATCH,
                        Map.of("tripId", trip.getId().toString(), "alertId", alert.getId().toString()));
                matches++;
            }
        }
        if (matches > 0) {
            log.info("Trajet {} : {} alerte(s) de recherche declenchee(s)", trip.getId(), matches);
        }
    }
}
