package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.trip.CreateTripRequest;
import bj.ekuiseo.api.dto.trip.PopularRouteResponse;
import bj.ekuiseo.api.dto.trip.StopRequest;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.dto.trip.TripStopResponse;
import bj.ekuiseo.api.dto.trip.UpdateTripRequest;
import bj.ekuiseo.api.mapper.TripMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TripService {

    /** Rayon de recherche par defaut (km) si non precise par le client. */
    private static final double DEFAULT_RADIUS_KM = 5.0;
    /** Plancher du rayon effectif (km) : en dessous, une adresse de quartier ne trouverait plus sa ville. */
    static final double MIN_RADIUS_KM = 1.0;
    /** Un arret intermediaire ne peut etre planifie au-dela de ce delai apres le depart (constat F419). */
    static final Duration MAX_STOP_DELAY = Duration.ofHours(24);
    /** Position conventionnelle de la destination parmi les points candidats d un trajet (voir TripRepository#search). */
    static final int DESTINATION_POSITION = 1_000_000;

    /**
     * Tri serveur de la recherche (constat F137). {@code DEPARTURE} par defaut ; la valeur est
     * traduite en une constante sure passee en parametre lie (jamais concatenee dans le SQL).
     */
    public enum SearchSort {
        DEPARTURE, PRICE, RATING;

        public static SearchSort from(String raw) {
            if (raw == null || raw.isBlank()) return DEPARTURE;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "price": return PRICE;
                case "rating": return RATING;
                case "departure": return DEPARTURE;
                default: throw new BadRequestException("Tri inconnu : " + raw + " (attendu : departure, price ou rating)");
            }
        }
    }

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final TripMapper tripMapper;
    private final BookingService bookingService;
    private final ApplicationEventPublisher eventPublisher;
    private final SearchEventService searchEventService;
    private final RecurrenceService recurrenceService;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;

    public TripService(TripRepository tripRepository, TripStopRepository tripStopRepository,
                        UserRepository userRepository, VehicleRepository vehicleRepository,
                        TripMapper tripMapper, BookingService bookingService,
                        ApplicationEventPublisher eventPublisher,
                        SearchEventService searchEventService, RecurrenceService recurrenceService,
                        BookingRepository bookingRepository, NotificationService notificationService) {
        this.recurrenceService = recurrenceService;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripMapper = tripMapper;
        this.bookingService = bookingService;
        this.eventPublisher = eventPublisher;
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
        if (vehicle.getDeletedAt() != null) {
            // Suppression logique (V15, constat F124) : la ligne existe encore pour l historique.
            throw new BadRequestException("Ce vehicule a ete supprime : choisissez ou ajoutez un autre vehicule");
        }
        if (req.seatsTotal() > vehicle.getSeats()) {
            throw new BadRequestException("Le nombre de places depasse la capacite du vehicule");
        }
        if (req.tripType() == TripType.QUOTIDIEN && (req.recurrenceRule() == null || req.recurrenceRule().isBlank())) {
            throw new BadRequestException("Une regle de recurrence est requise pour un trajet QUOTIDIEN");
        }
        List<StopRequest> stops = req.stops() == null ? List.of() : req.stops();
        validatePrices(req.pricePerSeat(), stops.stream().map(StopRequest::priceFromOrigin).toList());
        validateStopTimes(req.departureAt(), stops.stream().map(StopRequest::plannedAt).toList());
        // Une navette QUOTIDIEN est un modele (TEMPLATE, jamais cherchable) : ses occurrences,
        // generees ci-dessous sur 14 jours, sont les seuls trajets reservables (constats F041/F202).
        boolean recurring = req.tripType() == TripType.QUOTIDIEN;

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
                .status(recurring ? TripStatus.TEMPLATE : TripStatus.PUBLISHED)
                .recurrenceRule(recurring ? req.recurrenceRule() : null)
                .build();
        trip = tripRepository.save(trip);

        // Position 1..n pour les arrets intermediaires (l'origine du trajet est
        // conventionnellement la position 0, voir TripStopResponse et GET
        // /api/v1/trips/{id}/stops) : on demarre donc a 1, pas a 0.
        int position = 1;
        for (StopRequest s : stops) {
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

        if (recurring) {
            int generated = recurrenceService.generateFor(trip);
            if (generated == 0) {
                throw new BadRequestException("Aucun depart ne correspond a cette recurrence dans les 14 prochains jours : "
                        + "verifiez les jours choisis et la date du premier depart");
            }
            return tripMapper.toResponse(trip).withGeneratedOccurrences(generated);
        }
        // Matching des alertes de recherche apres commit, en asynchrone (constat F527) : un
        // echec du matching ne fait jamais echouer la publication.
        eventPublisher.publishEvent(new TripPublishedEvent(trip.getId()));
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
        return forRequester(tripMapper.toResponse(trip), requesterId);
    }

    /** Un appelant anonyme ne voit que l initiale du nom du conducteur (constat F519). */
    static TripResponse forRequester(TripResponse response, UUID requesterId) {
        return requesterId == null ? response.withAnonymizedDriver() : response;
    }

    /**
     * Bornes de prix (constats F040/F207) : prix par place strictement positif ; chaque
     * arret intermediaire entre 1 F et le prix par place, croissant avec la position
     * (un troncon plus long ne peut pas couter moins cher). Une reservation a 0 F serait
     * confirmee gratuitement en especes et impayable en mobile money.
     */
    static void validatePrices(long pricePerSeat, List<Long> stopPrices) {
        if (pricePerSeat <= 0) {
            throw new BadRequestException("Le prix par place doit etre superieur a 0 F");
        }
        long previous = 0;
        int position = 1;
        for (Long price : stopPrices) {
            long p = price == null ? 0 : price;
            if (p <= 0) {
                throw new BadRequestException("Le prix jusqu a l arret " + position + " doit etre superieur a 0 F");
            }
            if (p > pricePerSeat) {
                throw new BadRequestException("Le prix jusqu a l arret " + position + " (" + p
                        + " F) depasse le prix du trajet complet (" + pricePerSeat + " F)");
            }
            if (p < previous) {
                throw new BadRequestException("Le prix jusqu a l arret " + position + " doit etre au moins celui de l arret precedent");
            }
            previous = p;
            position++;
        }
    }

    /**
     * Heures de passage des arrets (constat F419) : facultatives, mais quand elles sont
     * donnees elles doivent tomber dans [depart, depart + 24 h] et croitre strictement avec
     * la position. Un arret « avant le depart » ou deux arrets a la meme minute sont des
     * erreurs de saisie qui rendraient l estimation d arrivee absurde pour le passager.
     */
    static void validateStopTimes(Instant departureAt, List<Instant> plannedAts) {
        if (departureAt == null) {
            return;
        }
        Instant latest = departureAt.plus(MAX_STOP_DELAY);
        Instant previous = null;
        int position = 1;
        for (Instant plannedAt : plannedAts) {
            if (plannedAt != null) {
                if (plannedAt.isBefore(departureAt)) {
                    throw new BadRequestException("L heure de passage a l arret " + position + " est anterieure au depart");
                }
                if (plannedAt.isAfter(latest)) {
                    throw new BadRequestException("L heure de passage a l arret " + position
                            + " est a plus de 24 h du depart : verifiez la date");
                }
                if (previous != null && !plannedAt.isAfter(previous)) {
                    throw new BadRequestException("L heure de passage a l arret " + position
                            + " doit etre posterieure a celle de l arret precedent");
                }
                previous = plannedAt;
            }
            position++;
        }
    }


    /**
     * Modification par le conducteur. Le trajet est charge sous verrou pessimiste
     * (constat F005) : une reservation concurrente qui decremente les places ne peut
     * plus etre ecrasee par une lecture perimee, et le statut FULL/PUBLISHED est
     * recalcule apres tout changement de places.
     *
     * <p>Lot 1.3 (constats F038/F208) : un trajet deja reserve ne peut plus changer
     * d itineraire ; un changement d horaire est notifie a chaque passager (SMS) et lui
     * ouvre 24 h d annulation gratuite. Sur un modele de navette (TEMPLATE), les
     * changements sont repercutes sur les occurrences a venir sans reservation.</p>
     */
    @Transactional
    public TripResponse updateTrip(UUID id, UUID driverId, UpdateTripRequest req) {
        Trip trip = tripRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        if (trip.getStatus() == TripStatus.ONGOING) {
            throw new BadRequestException("Ce trajet est en cours : il ne peut plus etre modifie");
        }
        if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
            throw new BadRequestException("Ce trajet ne peut plus etre modifie");
        }
        List<Booking> activeBookings = trip.getStatus() == TripStatus.TEMPLATE ? List.of()
                : bookingRepository.findByTripIdAndStatusIn(id, List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED));
        boolean routeChange = req.originLabel() != null || req.originLat() != null || req.originLng() != null
                || req.destLabel() != null || req.destLat() != null || req.destLng() != null;
        if (routeChange && !activeBookings.isEmpty()) {
            throw new BadRequestException("Des passagers ont deja reserve : l itineraire ne peut plus changer. "
                    + "Annulez ce trajet et publiez-en un nouveau.");
        }
        applyUpdate(trip, req);
        Instant previousDeparture = trip.getDepartureAt();
        boolean departureChanged = req.departureAt() != null && !req.departureAt().equals(previousDeparture);
        if (departureChanged) {
            trip.setDepartureAt(req.departureAt());
            // Le rappel de la veille doit repartir sur le nouvel horaire.
            trip.setReminderSentAt(null);
        }
        // Statut recalcule apres un changement de places : un trajet FULL qui gagne des
        // places redevient reservable, un trajet PUBLISHED sans place devient FULL.
        if (trip.getStatus() == TripStatus.FULL && trip.getSeatsAvailable() > 0) {
            trip.setStatus(TripStatus.PUBLISHED);
        } else if (trip.getStatus() == TripStatus.PUBLISHED && trip.getSeatsAvailable() == 0) {
            trip.setStatus(TripStatus.FULL);
        }
        trip = tripRepository.save(trip);

        if (departureChanged && !activeBookings.isEmpty()) {
            Instant freeUntil = Instant.now().plus(CancellationPolicy.FREE_CANCELLATION_WINDOW);
            for (Booking booking : activeBookings) {
                booking.setFreeCancellationUntil(freeUntil);
                bookingRepository.save(booking);
                notificationService.notifyCritical(booking.getPassenger(), NotificationType.TRIP_UPDATED,
                        Map.of("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                                "previousDepartureAt", previousDeparture.toString(),
                                "departureAt", trip.getDepartureAt().toString(),
                                "freeCancellationUntil", freeUntil.toString()),
                        "Ekuiseo : le depart de votre trajet " + trip.getOriginLabel() + " - " + trip.getDestLabel()
                                + " est deplace au " + BookingService.formatLocal(trip.getDepartureAt())
                                + ". Vous pouvez annuler sans frais pendant 24 h.");
            }
        }
        if (trip.getStatus() == TripStatus.TEMPLATE) {
            propagateTemplateUpdate(trip, departureChanged);
        } else if ((departureChanged || routeChange) && trip.getStatus() == TripStatus.PUBLISHED) {
            // Un trajet deplace ou reroute peut desormais correspondre a d autres alertes (constat
            // F535) : le matching est rejoue apres commit, dedoublonne par (alerte, trajet).
            eventPublisher.publishEvent(new TripPublishedEvent(trip.getId()));
        }
        return tripMapper.toResponse(trip);
    }

    /** Champs simples d une modification (hors horaire, traite a part pour la notification). */
    private void applyUpdate(Trip trip, UpdateTripRequest req) {
        if (req.originLabel() != null) {
            if (req.originLabel().isBlank()) {
                throw new BadRequestException("Le libelle d origine ne peut pas etre vide");
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
        if (req.pricePerSeat() != null) {
            validatePrices(req.pricePerSeat(), tripStopRepository.findByTripIdOrderByPosition(trip.getId()).stream()
                    .map(TripStop::getPriceFromOrigin).toList());
            trip.setPricePerSeat(req.pricePerSeat());
        }
        if (req.instantBooking() != null) trip.setInstantBooking(req.instantBooking());
        if (req.luggagePolicy() != null) trip.setLuggagePolicy(req.luggagePolicy());
        if (req.description() != null) trip.setDescription(req.description());
    }

    /**
     * Repercute une modification du modele sur ses occurrences a venir sans aucune
     * reservation (les occurrences reservees gardent leurs conditions : le passager a
     * accepte un prix et un horaire precis). Un changement d heure de depart deplace
     * chaque occurrence a la meme heure locale (fuseau du Benin).
     */
    private void propagateTemplateUpdate(Trip template, boolean departureChanged) {
        List<Trip> upcoming = tripRepository.findByParentTripIdAndStatusInAndDepartureAtAfter(
                template.getId(), List.of(TripStatus.PUBLISHED, TripStatus.FULL), Instant.now());
        java.time.LocalTime localTime = template.getDepartureAt().atZone(Tz.BENIN).toLocalTime();
        for (Trip occurrence : upcoming) {
            boolean booked = occurrence.getSeatsAvailable() < occurrence.getSeatsTotal();
            if (booked) {
                continue;
            }
            occurrence.setOriginLabel(template.getOriginLabel());
            occurrence.setOriginLat(template.getOriginLat());
            occurrence.setOriginLng(template.getOriginLng());
            occurrence.setDestLabel(template.getDestLabel());
            occurrence.setDestLat(template.getDestLat());
            occurrence.setDestLng(template.getDestLng());
            occurrence.setSeatsTotal(template.getSeatsTotal());
            occurrence.setSeatsAvailable(template.getSeatsTotal());
            occurrence.setPricePerSeat(template.getPricePerSeat());
            occurrence.setInstantBooking(template.isInstantBooking());
            occurrence.setLuggagePolicy(template.getLuggagePolicy());
            occurrence.setDescription(template.getDescription());
            if (departureChanged) {
                occurrence.setDepartureAt(occurrence.getDepartureAt().atZone(Tz.BENIN).toLocalDate()
                        .atTime(localTime).atZone(Tz.BENIN).toInstant());
                occurrence.setReminderSentAt(null);
            }
            occurrence.setStatus(TripStatus.PUBLISHED);
            tripRepository.save(occurrence);
            // Occurrence modifiee : les alertes sont rejouees, sans doublon (search_alert_matches
            // est cle par modele de navette, constats F533/F535).
            eventPublisher.publishEvent(new TripPublishedEvent(occurrence.getId()));
        }
    }

    /**
     * Annulation par le conducteur, avec cascade sur les reservations liees (regle
     * metier n.6bis) : reservations annulees, remboursement integral declenche,
     * passagers notifies, et annulation tardive comptabilisee dans les statistiques
     * du conducteur. Annuler un modele de navette annule toutes ses occurrences a venir
     * (constat F203) ; un trajet deja parti (ONGOING) ne s annule plus.
     */
    @Transactional
    public void cancelTrip(UUID id, UUID driverId) {
        Trip trip = findTrip(id);
        if (!trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        if (trip.getStatus() == TripStatus.ONGOING) {
            throw new BadRequestException("Ce trajet est en cours : il ne peut plus etre annule");
        }
        if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
            throw new BadRequestException("Ce trajet ne peut plus etre annule");
        }
        boolean template = trip.getStatus() == TripStatus.TEMPLATE;
        trip.setStatus(TripStatus.CANCELLED);
        tripRepository.save(trip);
        if (template) {
            List<Trip> upcoming = tripRepository.findByParentTripIdAndStatusInAndDepartureAtAfter(
                    trip.getId(), List.of(TripStatus.PUBLISHED, TripStatus.FULL), Instant.now());
            for (Trip occurrence : upcoming) {
                occurrence.setStatus(TripStatus.CANCELLED);
                tripRepository.save(occurrence);
                bookingService.cascadeCancelForDriverTripCancellation(occurrence);
            }
            return;
        }
        bookingService.cascadeCancelForDriverTripCancellation(trip);
    }

    /**
     * Recherche geospatiale (GET /api/v1/trips/search). Chaque recherche laisse une
     * trace dans search_events (indicateurs de liquidite du back-office), ecrite en
     * asynchrone par {@link SearchEventService#record} : la reponse ne l'attend pas.
     * Seule la premiere page compte comme une recherche - feuilleter les resultats
     * n'est pas chercher a nouveau.
     *
     * <p>Phase 2 (constats F137, F115/F409) : tri et filtres executes en SQL ; les arrets
     * intermediaires font partie des points candidats, et le troncon apparie (montee,
     * descente, prix du troncon) est renseigne sur chaque resultat qui passe par un arret,
     * en une requete {@code trip_stops} par page.</p>
     *
     * @param requesterId utilisateur connecte, ou null (endpoint public)
     * @param originLabel libelle tape par le passager, optionnel (n'influence pas la
     *                    recherche, qui est purement geographique ; ne sert qu'a la trace)
     * @param sort        departure (defaut), price ou rating
     * @param maxPrice    prix par place maximal (FCFA), optionnel
     * @param minRating   note minimale du conducteur, optionnelle
     * @param verifiedOnly ne garder que les conducteurs a identite verifiee
     */
    @Transactional(readOnly = true)
    public Page<TripResponse> search(UUID requesterId, String originLabel, String destLabel,
                                      double originLat, double originLng, double destLat, double destLng,
                                      LocalDate date, int seats, Double radiusKm, TripType tripType,
                                      String sort, Long maxPrice, Double minRating, Boolean verifiedOnly,
                                      Pageable pageable) {
        double effectiveRadiusKm = effectiveRadiusKm(radiusKm != null ? radiusKm : DEFAULT_RADIUS_KM,
                originLat, originLng, destLat, destLng);
        double radiusMeters = effectiveRadiusKm * 1000.0;
        // Jour civil du Benin (constat F415) ; les trajets deja partis sont exclus par la requete.
        Instant dateFrom = date != null ? date.atStartOfDay(Tz.BENIN).toInstant() : null;
        Instant dateTo = date != null ? date.plusDays(1).atStartOfDay(Tz.BENIN).toInstant() : null;
        if (maxPrice != null && maxPrice <= 0) {
            throw new BadRequestException("Le prix maximal doit etre superieur a 0 F");
        }
        if (minRating != null && (minRating < 0 || minRating > 5)) {
            throw new BadRequestException("La note minimale doit etre comprise entre 0 et 5");
        }
        SearchSort searchSort = SearchSort.from(sort);
        Page<Trip> page = tripRepository.search(originLat, originLng, destLat, destLng, radiusMeters, seats,
                tripType != null ? tripType.name() : null, dateFrom, dateTo, Instant.now(),
                searchSort.name(), maxPrice, minRating, Boolean.TRUE.equals(verifiedOnly), pageable);
        if (pageable.getPageNumber() == 0) {
            searchEventService.record(requesterId,
                    new SearchEventService.SearchRequest(originLabel, originLat, originLng,
                            destLabel, destLat, destLng, date, seats, effectiveRadiusKm, tripType),
                    page.getTotalElements());
        }
        List<TripResponse> content = enrichSegments(page.getContent(), originLat, originLng, destLat, destLng, effectiveRadiusKm)
                .stream().map(t -> forRequester(t, requesterId)).toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /** Variante historique sans tri ni filtres (tri par depart, aucun filtre). */
    @Transactional(readOnly = true)
    public Page<TripResponse> search(UUID requesterId, String originLabel, String destLabel,
                                      double originLat, double originLng, double destLat, double destLng,
                                      LocalDate date, int seats, Double radiusKm, TripType tripType,
                                      Pageable pageable) {
        return search(requesterId, originLabel, destLabel, originLat, originLng, destLat, destLng, date, seats,
                radiusKm, tripType, null, null, null, null, pageable);
    }

    /**
     * Troncon apparie de chaque resultat (constats F115/F409) : une seule requete
     * trip_stops pour la page, puis la meme regle qu en SQL en memoire (point de montee
     * dans le rayon de l origine cherchee, point de descente de position strictement
     * superieure dans le rayon de la destination, chacun plus proche de l extremite qu il
     * sert). Les champs restent nuls quand le trajet correspond d origine a destination.
     */
    List<TripResponse> enrichSegments(List<Trip> trips, double originLat, double originLng,
                                      double destLat, double destLng, double radiusKm) {
        if (trips.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = trips.stream().map(Trip::getId).toList();
        Map<UUID, List<TripStop>> stopsByTrip = new HashMap<>();
        for (TripStop stop : tripStopRepository.findByTripIdInOrderByPositionAsc(ids)) {
            stopsByTrip.computeIfAbsent(stop.getTrip().getId(), k -> new ArrayList<>()).add(stop);
        }
        List<TripResponse> result = new ArrayList<>(trips.size());
        for (Trip trip : trips) {
            TripResponse base = tripMapper.toResponse(trip);
            Segment segment = matchSegment(trip, stopsByTrip.getOrDefault(trip.getId(), List.of()),
                    originLat, originLng, destLat, destLng, radiusKm);
            result.add(segment == null ? base
                    : base.withSegment(segment.pickupStopId(), segment.dropoffStopId(), segment.priceFcfa()));
        }
        return result;
    }

    /** Troncon montee -> descente apparie a une recherche ; null quand il s agit du trajet complet. */
    record Segment(UUID pickupStopId, UUID dropoffStopId, long priceFcfa) {
    }

    /** Point candidat d un trajet : origine (position 0, prix 0), arret, ou destination (prix par place). */
    private record Candidate(UUID stopId, int position, double lat, double lng, long priceFromOrigin) {
    }

    static Segment matchSegment(Trip trip, List<TripStop> stops, double originLat, double originLng,
                                double destLat, double destLng, double radiusKm) {
        List<Candidate> candidates = new ArrayList<>(stops.size() + 2);
        candidates.add(new Candidate(null, 0, trip.getOriginLat(), trip.getOriginLng(), 0L));
        for (TripStop stop : stops) {
            candidates.add(new Candidate(stop.getId(), stop.getPosition(), stop.getLat(), stop.getLng(), stop.getPriceFromOrigin()));
        }
        candidates.add(new Candidate(null, DESTINATION_POSITION, trip.getDestLat(), trip.getDestLng(), trip.getPricePerSeat()));

        Candidate bestPickup = null;
        Candidate bestDropoff = null;
        double bestPickupDistance = Double.MAX_VALUE;
        for (Candidate pickup : candidates) {
            double toOrigin = haversineKm(pickup.lat(), pickup.lng(), originLat, originLng);
            double toDest = haversineKm(pickup.lat(), pickup.lng(), destLat, destLng);
            if (toOrigin > radiusKm || toOrigin >= toDest || toOrigin >= bestPickupDistance) {
                continue;
            }
            Candidate dropoff = null;
            double bestDropoffDistance = Double.MAX_VALUE;
            for (Candidate candidate : candidates) {
                if (candidate.position() <= pickup.position()) {
                    continue;
                }
                double dToDest = haversineKm(candidate.lat(), candidate.lng(), destLat, destLng);
                double dToOrigin = haversineKm(candidate.lat(), candidate.lng(), originLat, originLng);
                if (dToDest <= radiusKm && dToDest < dToOrigin && dToDest < bestDropoffDistance) {
                    dropoff = candidate;
                    bestDropoffDistance = dToDest;
                }
            }
            if (dropoff != null) {
                bestPickup = pickup;
                bestDropoff = dropoff;
                bestPickupDistance = toOrigin;
            }
        }
        if (bestPickup == null || (bestPickup.stopId() == null && bestDropoff.stopId() == null)) {
            return null;
        }
        return new Segment(bestPickup.stopId(), bestDropoff.stopId(),
                Math.max(0L, bestDropoff.priceFromOrigin() - bestPickup.priceFromOrigin()));
    }

    /**
     * Rayon effectif (constat F408) : jamais plus de la moitie de la distance entre les deux
     * points cherches, sinon les deux disques se recouvrent et un trajet en sens inverse
     * satisfait les deux ST_DWithin ; jamais moins de {@link #MIN_RADIUS_KM}.
     */
    static double effectiveRadiusKm(double requestedKm, double originLat, double originLng, double destLat, double destLng) {
        double halfAxis = haversineKm(originLat, originLng, destLat, destLng) / 2.0;
        return Math.max(MIN_RADIUS_KM, Math.min(requestedKm, halfAxis));
    }

    /** Distance orthodromique (km) entre deux points, rayon terrestre moyen 6 371 km. */
    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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
