package bj.ekuiseo.api.service;

import bj.ekuiseo.api.config.AsyncConfig;
import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.domain.SearchEvent;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import bj.ekuiseo.api.repository.SearchEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Trace des recherches de trajets (table search_events, migration V9), ecrite en
 * asynchrone pour ne jamais ralentir la recherche elle-meme (voir AsyncConfig).
 *
 * <p>Le rattachement a une ville du referentiel geo_places (cle de regroupement
 * des axes) se fait ici, a l'ecriture, et non a la lecture : c'est une requete
 * geographique par point, bon marche une fois par recherche, couteuse a refaire a
 * chaque affichage du tableau de bord.</p>
 */
@Service
public class SearchEventService {

    private static final Logger log = LoggerFactory.getLogger(SearchEventService.class);

    /** Au-dela de cette distance, aucune ville du referentiel n'est rattachee (lieu inconnu, frontiere...). */
    static final double PLACE_MATCH_RADIUS_METERS = 30_000;
    private static final int LABEL_MAX_LENGTH = 255;

    /** Ce que le passager a demande, tel que recu par GET /api/v1/trips/search. */
    public record SearchRequest(String originLabel, double originLat, double originLng,
                                String destLabel, double destLat, double destLng,
                                LocalDate date, int seats, double radiusKm, TripType tripType) {
    }

    private final SearchEventRepository searchEventRepository;
    private final GeoPlaceRepository geoPlaceRepository;

    public SearchEventService(SearchEventRepository searchEventRepository, GeoPlaceRepository geoPlaceRepository) {
        this.searchEventRepository = searchEventRepository;
        this.geoPlaceRepository = geoPlaceRepository;
    }

    /**
     * Enregistre une recherche, hors du fil de la requete HTTP. Ne leve jamais :
     * une trace perdue est journalisee, jamais propagee (elle s'executerait de toute
     * facon sur un autre fil, apres que la reponse a ete envoyee).
     *
     * @param userId      utilisateur connecte, ou null pour une recherche anonyme
     * @param resultCount nombre total de trajets renvoyes (toutes pages confondues)
     */
    @Async(AsyncConfig.SEARCH_EVENT_EXECUTOR)
    public void record(UUID userId, SearchRequest request, long resultCount) {
        try {
            SearchEvent event = buildEvent(userId, request, resultCount);
            searchEventRepository.save(event);
        } catch (RuntimeException e) {
            log.warn("Trace de recherche non enregistree : {}", e.getMessage());
        }
    }

    /** Construction pure (testable sans contexte asynchrone) : rattachement aux villes et bornage des libelles. */
    SearchEvent buildEvent(UUID userId, SearchRequest request, long resultCount) {
        UUID originPlaceId = nearestCityId(request.originLat(), request.originLng());
        UUID destPlaceId = nearestCityId(request.destLat(), request.destLng());
        return SearchEvent.builder()
                .userId(userId)
                .originLabel(truncate(request.originLabel()))
                .originLat(request.originLat())
                .originLng(request.originLng())
                .originPlaceId(originPlaceId)
                .destLabel(truncate(request.destLabel()))
                .destLat(request.destLat())
                .destLng(request.destLng())
                .destPlaceId(destPlaceId)
                .requestedDate(request.date())
                .seats(Math.max(1, request.seats()))
                .tripType(request.tripType())
                .radiusKm(request.radiusKm())
                .resultCount((int) Math.min(Integer.MAX_VALUE, Math.max(0, resultCount)))
                .build();
    }

    /**
     * Purge de retention (voir SearchEventRetentionScheduler) : supprime les traces
     * plus anciennes que {@code retentionDays}. Duree declaree dans docs/CONFORMITE.md.
     */
    @Transactional
    public int purgeOlderThan(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        return searchEventRepository.deleteOlderThan(cutoff);
    }

    private UUID nearestCityId(double lat, double lng) {
        return geoPlaceRepository.findNearestCity(lat, lng, PLACE_MATCH_RADIUS_METERS)
                .map(GeoPlace::getId)
                .orElse(null);
    }

    private static String truncate(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= LABEL_MAX_LENGTH ? trimmed : trimmed.substring(0, LABEL_MAX_LENGTH);
    }
}
