package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.UnprocessableEntityException;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.alert.TripAlertResponse;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Alertes de recherche (regle metier n.13, /api/v1/trip-alerts). Phase 2 de l audit
 * (constats F514/F523/F524/F525/F530) :
 * <ul>
 *   <li>liste et suppression par leur proprietaire ;</li>
 *   <li>dedoublonnage : memes coordonnees (3 decimales, ~100 m), meme date, meme type et
 *       memes places qu une alerte active existante -> l existante est renvoyee, rien n est
 *       cree ;</li>
 *   <li>au plus {@link #MAX_ACTIVE_ALERTS} alertes actives par utilisateur (422) ;</li>
 *   <li>une alerte sans date est bornee a {@link #UNDATED_ALERT_DAYS} jours ;</li>
 *   <li>le rayon de la recherche d origine est fige sur l alerte (radius_km, V16) ;</li>
 *   <li>l e-mail est active dans les preferences si l utilisateur ne les a jamais
 *       touchees (aucune ligne user_preferences) : une alerte qui ne previent qu in-app ne
 *       previent personne. Un utilisateur qui a deja regle ses preferences les garde.</li>
 * </ul>
 */
@Service
public class TripAlertService {

    private static final Logger log = LoggerFactory.getLogger(TripAlertService.class);

    static final int MAX_ACTIVE_ALERTS = 10;
    static final int UNDATED_ALERT_DAYS = 30;

    private final SearchAlertRepository searchAlertRepository;
    private final UserRepository userRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final double defaultRadiusKm;

    public TripAlertService(SearchAlertRepository searchAlertRepository, UserRepository userRepository,
                            UserPreferencesRepository userPreferencesRepository,
                            @Value("${ekuiseo.search-alert.radius-km:15}") double defaultRadiusKm) {
        this.searchAlertRepository = searchAlertRepository;
        this.userRepository = userRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.defaultRadiusKm = defaultRadiusKm;
    }

    @Transactional
    public TripAlertResponse create(UUID userId, TripAlertRequest req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        List<SearchAlert> active = searchAlertRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        for (SearchAlert existing : active) {
            if (isSameAlert(existing, req)) {
                return toResponse(existing);
            }
        }
        if (active.size() >= MAX_ACTIVE_ALERTS) {
            throw new UnprocessableEntityException("Vous avez deja " + MAX_ACTIVE_ALERTS
                    + " alertes actives : supprimez-en une avant d en creer une nouvelle");
        }
        LocalDate today = LocalDate.now(Tz.BENIN);
        SearchAlert alert = SearchAlert.builder()
                .user(user)
                .originLabel(req.originLabel())
                .originLat(req.originLat())
                .originLng(req.originLng())
                .destLabel(req.destLabel())
                .destLat(req.destLat())
                .destLng(req.destLng())
                // Le front ne cible qu'une seule date (pas de plage) : fenetre d'un seul jour ;
                // sans date, la fenetre court 30 jours (constat F525).
                .dateFrom(req.date())
                .dateTo(req.date() != null ? req.date() : today.plusDays(UNDATED_ALERT_DAYS))
                .seats(req.seats())
                .tripType(req.tripType())
                .radiusKm(req.radiusKm() != null ? req.radiusKm() : defaultRadiusKm)
                .active(true)
                .build();
        SearchAlert saved = searchAlertRepository.save(alert);
        enableEmailIfUntouched(user);
        return toResponse(saved);
    }

    /** Coordonnees arrondies a 3 decimales (~100 m), meme date, meme type, memes places. */
    static boolean isSameAlert(SearchAlert existing, TripAlertRequest req) {
        return round3(existing.getOriginLat()) == round3(req.originLat())
                && round3(existing.getOriginLng()) == round3(req.originLng())
                && round3(existing.getDestLat()) == round3(req.destLat())
                && round3(existing.getDestLng()) == round3(req.destLng())
                && Objects.equals(existing.getDateFrom(), req.date())
                && existing.getTripType() == req.tripType()
                && existing.getSeats() == req.seats();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /**
     * Choix documente (constat F523) : les preferences par defaut n envoient pas d e-mail.
     * Si l utilisateur n a jamais consulte ni modifie ses preferences (aucune ligne), la
     * creation d une alerte cree la ligne avec l e-mail active : c est le seul canal sortant
     * qui puisse honorer la promesse « etre prevenu ». Une ligne existante n est jamais
     * modifiee : un refus explicite de l e-mail est respecte.
     */
    private void enableEmailIfUntouched(User user) {
        if (userPreferencesRepository.findByUserId(user.getId()).isPresent()) {
            return;
        }
        UserPreferences prefs = UserPreferences.builder().user(user).notifyByEmail(true).build();
        userPreferencesRepository.save(prefs);
        log.info("Alerte de recherche : e-mail active par defaut pour l utilisateur {}", user.getId());
    }

    @Transactional(readOnly = true)
    public List<TripAlertResponse> list(UUID userId) {
        return searchAlertRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    /** DELETE /api/v1/trip-alerts/{id} : reserve au proprietaire (403 sinon), la ligne est supprimee. */
    @Transactional
    public void delete(UUID userId, UUID alertId) {
        SearchAlert alert = searchAlertRepository.findById(alertId)
                .orElseThrow(() -> new NotFoundException("Alerte introuvable"));
        if (!alert.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Cette alerte ne vous appartient pas");
        }
        searchAlertRepository.delete(alert);
    }

    private TripAlertResponse toResponse(SearchAlert a) {
        return new TripAlertResponse(a.getId(), a.getOriginLabel(), a.getOriginLat(), a.getOriginLng(),
                a.getDestLabel(), a.getDestLat(), a.getDestLng(), a.getDateFrom(), a.getSeats(), a.getTripType(),
                a.getCreatedAt(), a.isActive(), a.getRadiusKm(), a.getDateTo());
    }
}
