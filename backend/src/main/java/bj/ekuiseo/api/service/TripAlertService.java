package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.alert.TripAlertResponse;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Alertes de recherche (regle metier n.13, POST /api/v1/trip-alerts). */
@Service
public class TripAlertService {

    private final SearchAlertRepository searchAlertRepository;
    private final UserRepository userRepository;

    public TripAlertService(SearchAlertRepository searchAlertRepository, UserRepository userRepository) {
        this.searchAlertRepository = searchAlertRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TripAlertResponse create(UUID userId, TripAlertRequest req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        SearchAlert alert = SearchAlert.builder()
                .user(user)
                .originLabel(req.originLabel())
                .originLat(req.originLat())
                .originLng(req.originLng())
                .destLabel(req.destLabel())
                .destLat(req.destLat())
                .destLng(req.destLng())
                // Le front ne cible qu'une seule date (pas de plage) : on la stocke
                // comme une fenetre d'un seul jour.
                .dateFrom(req.date())
                .dateTo(req.date())
                .seats(req.seats())
                .tripType(req.tripType())
                .active(true)
                .build();
        return toResponse(searchAlertRepository.save(alert));
    }

    private TripAlertResponse toResponse(SearchAlert a) {
        return new TripAlertResponse(a.getId(), a.getOriginLabel(), a.getOriginLat(), a.getOriginLng(),
                a.getDestLabel(), a.getDestLat(), a.getDestLng(), a.getDateFrom(), a.getSeats(), a.getTripType(),
                a.getCreatedAt(), a.isActive());
    }
}
