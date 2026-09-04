package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.trip.CreateTripRequest;
import bj.ekuiseo.api.dto.trip.PopularRouteResponse;
import bj.ekuiseo.api.dto.trip.StopRequest;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.dto.trip.TripStopResponse;
import bj.ekuiseo.api.dto.trip.UpdateTripRequest;
import bj.ekuiseo.api.mapper.TripMapper;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class TripService {

    /** Rayon de recherche par defaut (km) si non precise par le client. */
    private static final double DEFAULT_RADIUS_KM = 5.0;

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final TripMapper tripMapper;
    private final BookingService bookingService;
    private final SearchAlertMatchService searchAlertMatchService;
    private final SearchEventService searchEventService;

    public TripService(TripRepository tripRepository, TripStopRepository tripStopRepository,
                        UserRepository userRepository, VehicleRepository vehicleRepository,
                        TripMapper tripMapper, BookingService bookingService,
                        SearchAlertMatchService searchAlertMatchService,
                        SearchEventService searchEventService) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripMapper = tripMapper;
        this.bookingService = bookingService;
        this.searchAlertMatchService = searchAlertMatchService;
        this.searchEventService = searchEventService;
    }

    @Transactional
    public TripResponse createTrip(UUID driverId, CreateTripRequest req) {
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new NotFoundException("Conducteur introuvable"));
        Vehicle vehicle = vehicleRepository.findById(req.vehicleId())
                .orElseThrow(() -> new NotFoundException("Vehicule introuvable"));
        if (!vehicle.getOwner().getId().equals(driverId)) {
            throw new ForbiddenException("Ce vehicule ne vous appartient pas");
        }
        if (req.seatsTotal() > vehicle.getSeats()) {
            throw new BadRequestException("Le nombre de places depasse la capacite du vehicule");
        }
        if (req.tripType() == TripType.QUOTIDIEN && (req.recurrenceRule() == null || req.recurrenceRule().isBlank())) {
            throw new BadRequestException("Une regle de recurrence est requise pour un trajet QUOTIDIEN");
        }

        Trip trip = Trip.builder()
                .driver(driver)
                .vehicle(vehicle)
                .tripType(req.tripType())
                .originLabel(req.originLabel())
                .originLat(req.originLat())
                .originLng(req.originLng())
                .destLabel(req.destLabel())
                .destLat(req.destLat())
                .destLng(req.destLng())
                .departureAt(req.departureAt())
                .seatsTotal(req.seatsTotal())
                .seatsAvailable(req.seatsTotal())
                .pricePerSeat(req.pricePerSeat())
                .instantBooking(req.instantBooking())
                .luggagePolicy(req.luggagePolicy())
                .description(req.description())
                .status(TripStatus.PUBLISHED)
                .recurrenceRule(req.tripType() == TripType.QUOTIDIEN ? req.recurrenceRule() : null)
                .build();
        trip = tripRepository.save(trip);

        if (req.stops() != null) {
            // Position 1..n pour les arrets intermediaires (l'origine du trajet est
            // conventionnellement la position 0, voir TripStopResponse et GET
            // /api/v1/trips/{id}/stops) : on demarre donc a 1, pas a 0.
            int position = 1;
            for (StopRequest s : req.stops()) {
                tripStopRepository.save(TripStop.builder()
                        .trip(trip)
                        .position(position++)
                        .label(s.label())
                        .lat(s.lat())
                        .lng(s.lng())
                        .plannedAt(s.plannedAt())
                        .priceFromOrigin(s.priceFromOrigin())
                        .build());
            }
        }

        if (trip.getStatus() == TripStatus.PUBLISHED) {
            searchAlertMatchService.notifyMatchingAlerts(trip);
        }
        return tripMapper.toResponse(trip);
    }

    /**
     * Consultation d'un trajet. Un trajet DRAFT n'est visible que par son conducteur
     * (regle metier n.6ter, corrigee) : requesterId est null quand l'appel est anonyme
     * (l'endpoint est public pour un trajet PUBLISHED, voir SecurityConfig), auquel cas
     * un brouillon est traite comme inexistant plutot que de reveler son existence.
     */
    @Transactional(readOnly = true)
    public TripResponse getTrip(UUID id, UUID requesterId) {
        Trip trip = findTrip(id);
        if (trip.getStatus() == TripStatus.DRAFT
                && (requesterId == null || !trip.getDriver().getId().equals(requesterId))) {
            throw new NotFoundException("Trajet introuvable");
        }
        return tripMapper.toResponse(trip);
    }

    @Transactional
    public TripResponse updateTrip(UUID id, UUID driverId, UpdateTripRequest req) {
        Trip trip = findTrip(id);
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n'etes pas le conducteur de ce trajet");
        }
        if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
            throw new BadRequestException("Ce trajet ne peut plus etre modifie");
        }
        if (req.originLabel() != null) {
            if (req.originLabel().isBlank()) {
                throw new BadRequestException("Le libelle d'origine ne peut pas etre vide");
            }
            trip.setOriginLabel(req.originLabel());
        }
        if (req.originLat() != null) trip.setOriginLat(req.originLat());
        if (req.originLng() != null) trip.setOriginLng(req.originLng());
        if (req.destLabel() != null) {
            if (req.destLabel().isBlank()) {
                throw new BadRequestException("Le libelle de destination ne peut pas etre vide");
            }
            trip.setDestLabel(req.destLabel());
        }
        if (req.destLat() != null) trip.setDestLat(req.destLat());
        if (req.destLng() != null) trip.setDestLng(req.destLng());
        if (req.departureAt() != null) trip.setDepartureAt(req.departureAt());
        if (req.seatsTotal() != null) {
            // Regle metier n.6bis (corrigee) : le nouveau total ne doit jamais depasser la
            // capacite du vehicule, ni descendre sous le nombre de places deja reservees.
            int alreadyBooked = trip.getSeatsTotal() - trip.getSeatsAvailable();
            if (req.seatsTotal() > trip.getVehicle().getSeats()) {
                throw new BadRequestException("Le nombre de places depasse la capacite du vehicule");
            }
            if (req.seatsTotal() < alreadyBooked) {
                throw new BadRequestException(
                        "Le nombre de places ne peut pas etre inferieur aux " + alreadyBooked + " places deja reservees");
            }
            int delta = req.seatsTotal() - trip.getSeatsTotal();
            trip.setSeatsTotal(req.seatsTotal());
            trip.setSeatsAvailable(trip.getSeatsAvailable() + delta);
        }
        if (req.pricePerSeat() != null) trip.setPricePerSeat(req.pricePerSeat());
        if (req.instantBooking() != null) trip.setInstantBooking(req.instantBooking());
        if (req.luggagePolicy() != null) trip.setLuggagePolicy(req.luggagePolicy());
        if (req.description() != null) trip.setDescription(req.description());
        return tripMapper.toResponse(tripRepository.save(trip));
    }

    /**
     * Annulation par le conducteur, avec cascade sur les reservations liees (regle
     * metier n.6bis) : reservations annulees, remboursement integral declenche,
     * passagers notifies, et annulation tardive comptabilisee dans les statistiques
     * du conducteur.
     */
    @Transactional
    public void cancelTrip(UUID id, UUID driverId) {
        Trip trip = findTrip(id);
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n'etes pas le conducteur de ce trajet");
        }
        if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
            throw new BadRequestException("Ce trajet ne peut plus etre annule");
        }
        trip.setStatus(TripStatus.CANCELLED);
        tripRepository.save(trip);
        bookingService.cascadeCancelForDriverTripCancellation(trip);
    }

    /**
     * Recherche geospatiale (GET /api/v1/trips/search). Chaque recherche laisse une
     * trace dans search_events (indicateurs de liquidite du back-office), ecrite en
     * asynchrone par {@link SearchEventService#record} : la reponse ne l'attend pas.
     * Seule la premiere page compte comme une recherche - feuilleter les resultats
     * n'est pas chercher a nouveau.
     *
     * @param requesterId utilisateur connecte, ou null (endpoint public)
     * @param originLabel libelle tape par le passager, optionnel (n'influence pas la
     *                    recherche, qui est purement geographique ; ne sert qu'a la trace)
     */
    @Transactional(readOnly = true)
    public Page<TripResponse> search(UUID requesterId, String originLabel, String destLabel,
                                      double originLat, double originLng, double destLat, double destLng,
                                      LocalDate date, int seats, Double radiusKm, TripType tripType,
                                      Pageable pageable) {
        double effectiveRadiusKm = radiusKm != null ? radiusKm : DEFAULT_RADIUS_KM;
        double radiusMeters = effectiveRadiusKm * 1000.0;
        Instant dateFrom = date != null ? date.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant dateTo = date != null ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Page<Trip> page = tripRepository.search(originLat, originLng, destLat, destLng, radiusMeters, seats,
                tripType != null ? tripType.name() : null, dateFrom, dateTo, pageable);
        if (pageable.getPageNumber() == 0) {
            searchEventService.record(requesterId,
                    new SearchEventService.SearchRequest(originLabel, originLat, originLng,
                            destLabel, destLat, destLng, date, seats, effectiveRadiusKm, tripType),
                    page.getTotalElements());
        }
        return page.map(tripMapper::toResponse);
    }

    /** Axes les plus proposes en ce moment (GET /api/v1/trips/popular), public, borne a 12 resultats. */
    @Transactional(readOnly = true)
    public List<PopularRouteResponse> popularRoutes(int limit) {
        int bounded = Math.max(1, Math.min(12, limit));
        return tripRepository.findPopularRoutes(Instant.now(), bounded).stream()
                .map(r -> new PopularRouteResponse(
                        r.getOriginLabel(), nz(r.getOriginLat()), nz(r.getOriginLng()),
                        r.getDestLabel(), nz(r.getDestLat()), nz(r.getDestLng()),
                        r.getTrips(), r.getMinPrice()))
                .toList();
    }

    private static double nz(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * Arrets intermediaires d'un trajet, tries par position (GET /api/v1/trips/{id}/stops,
     * public au meme titre que la consultation du trajet lui-meme - voir SecurityConfig).
     * Un trajet DRAFT non visible par l'appelant est traite comme inexistant, comme
     * pour {@link #getTrip}.
     */
    @Transactional(readOnly = true)
    public List<TripStopResponse> getStops(UUID id, UUID requesterId) {
        Trip trip = findTrip(id);
        if (trip.getStatus() == TripStatus.DRAFT
                && (requesterId == null || !trip.getDriver().getId().equals(requesterId))) {
            throw new NotFoundException("Trajet introuvable");
        }
        return tripStopRepository.findByTripIdOrderByPosition(id).stream().map(tripMapper::toStopResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<TripResponse> myTrips(UUID driverId) {
        return tripRepository.findByDriverIdOrderByDepartureAtDesc(driverId).stream()
                .map(tripMapper::toResponse).toList();
    }

    Trip findTrip(UUID id) {
        return tripRepository.findById(id).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
    }
}
