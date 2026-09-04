package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;
import bj.ekuiseo.api.dto.review.CreateReviewRequest;
import bj.ekuiseo.api.dto.review.ReviewResponse;
import bj.ekuiseo.api.dto.trip.CreateTripRequest;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.dto.trip.TripStopResponse;
import bj.ekuiseo.api.dto.trip.UpdateTripRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.ReviewService;
import bj.ekuiseo.api.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Trajets", description = "Publication, recherche, mise a jour et annulation des trajets ; reservation et avis rattaches a un trajet")
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;
    private final BookingService bookingService;
    private final ReviewService reviewService;
    private final CurrentUser currentUser;

    public TripController(TripService tripService, BookingService bookingService, ReviewService reviewService,
                           CurrentUser currentUser) {
        this.tripService = tripService;
        this.bookingService = bookingService;
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Publier un trajet", description = "Cree un trajet PUBLISHED (interurbain ou quotidien recurrent). Le nombre de places ne peut pas depasser la capacite du vehicule.")
    @PostMapping
    public ResponseEntity<TripResponse> create(@Valid @RequestBody CreateTripRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.createTrip(currentUser.id(), req));
    }

    @Operation(summary = "Rechercher des trajets", description = "Recherche geospatiale : origine ET destination a moins de radiusKm (5 km par defaut) des points donnes, trie par pertinence. originLabel/destLabel (optionnels) ne filtrent rien : ils lisibilisent la trace de recherche conservee pour les indicateurs de liquidite du back-office (table search_events).")
    @GetMapping("/search")
    public Page<TripResponse> search(@RequestParam double originLat,
                                      @RequestParam double originLng,
                                      @RequestParam double destLat,
                                      @RequestParam double destLng,
                                      @RequestParam(required = false) String originLabel,
                                      @RequestParam(required = false) String destLabel,
                                      @RequestParam(required = false) LocalDate date,
                                      @RequestParam(defaultValue = "1") int seats,
                                      @RequestParam(required = false) Double radiusKm,
                                      @RequestParam(required = false) TripType tripType,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        // Endpoint public : requesterId est null pour un appelant anonyme (la trace de
        // recherche est alors anonyme elle aussi).
        return tripService.search(currentUser.idOrNull(), originLabel, destLabel,
                originLat, originLng, destLat, destLng, date, seats, radiusKm, tripType, pageable);
    }

    @Operation(summary = "Consulter un trajet", description = "Public pour un trajet PUBLISHED. Un trajet DRAFT renvoie 404 pour toute personne autre que son conducteur (jamais 403, pour ne pas reveler son existence).")
    @GetMapping("/{id}")
    public TripResponse get(@PathVariable UUID id) {
        // Endpoint public pour un trajet PUBLISHED (voir SecurityConfig) : requesterId est
        // null pour un appelant anonyme. TripService restreint alors la visibilite d'un
        // brouillon (DRAFT) a son seul conducteur.
        return tripService.getTrip(id, currentUser.idOrNull());
    }

    @Operation(summary = "Arrets intermediaires du trajet", description = "Prix par troncon depuis l'origine. Meme visibilite que GET /api/v1/trips/{id} (public pour un trajet PUBLISHED).")
    @GetMapping("/{id}/stops")
    public List<TripStopResponse> stops(@PathVariable UUID id) {
        return tripService.getStops(id, currentUser.idOrNull());
    }

    @Operation(summary = "Modifier un trajet (PATCH partiel)", description = "Reserve au conducteur du trajet. Le nombre de places ne peut ni depasser la capacite du vehicule, ni descendre sous le nombre de places deja reservees.")
    @PatchMapping("/{id}")
    public TripResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTripRequest req) {
        return tripService.updateTrip(id, currentUser.id(), req);
    }

    @Operation(summary = "Annuler un trajet", description = "Reserve au conducteur. Annule en cascade toutes les reservations actives, declenche leur remboursement integral, notifie les passagers, et penalise le conducteur si l'annulation est tardive.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        tripService.cancelTrip(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reserver des places sur ce trajet")
    @PostMapping("/{id}/bookings")
    public ResponseEntity<BookingResponse> book(@PathVariable UUID id, @Valid @RequestBody CreateBookingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.createBooking(id, currentUser.id(), req));
    }

    @Operation(summary = "Devis de reservation", description = "Calcule le plan de paiement exact (total, frais de service, acompte, solde a bord) SANS rien creer en base - meme FeePolicy que la reservation reelle, voir BookingService#quote. Refuse si le trajet est complet/annule/termine ou si l'appelant en est le conducteur.")
    @PostMapping("/{id}/booking-quote")
    public PaymentPlanResponse quote(@PathVariable UUID id, @Valid @RequestBody BookingQuoteRequest req) {
        return bookingService.quote(id, currentUser.id(), req);
    }

    @Operation(summary = "Laisser un avis sur ce trajet", description = "Reserve aux participants (conducteur et passagers) du trajet, apres son deroulement.")
    @PostMapping("/{id}/reviews")
    public ResponseEntity<ReviewResponse> review(@PathVariable UUID id, @Valid @RequestBody CreateReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(id, currentUser.id(), req));
    }
}
