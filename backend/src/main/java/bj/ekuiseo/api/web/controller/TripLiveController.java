package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingRequest;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.TripLiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Suivi en direct d un trajet (V23) : partage de position par le conducteur, lecture par
 * ses passagers, lien public a jeton. Les routes {@code /api/v1/trips/{id}/live*} sont
 * authentifiees ; {@code GET /api/v1/live/{token}} est publique (SecurityConfig) et bornee
 * par IP (RateLimitingFilter, quota live-public).
 */
@Tag(name = "Suivi en direct", description = "Position du vehicule pendant le trajet : partage par le conducteur, lecture par les passagers, lien public a jeton")
@RestController
@Validated
public class TripLiveController {

    private final TripLiveService tripLiveService;
    private final CurrentUser currentUser;

    public TripLiveController(TripLiveService tripLiveService, CurrentUser currentUser) {
        this.tripLiveService = tripLiveService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Activer ou couper le partage de position", description = "Reserve au conducteur, trajet PUBLISHED / FULL / ONGOING. Le jeton du lien public est genere a la premiere activation et conserve tant que le trajet vit.")
    @PutMapping("/api/v1/trips/{id}/live")
    public LiveSharingResponse setSharing(@PathVariable UUID id, @Valid @RequestBody LiveSharingRequest req) {
        return tripLiveService.setSharing(id, currentUser.id(), req.enabled());
    }

    @Operation(summary = "Envoyer une position", description = "Reserve au conducteur ; 400 si le partage n est pas active ou si le trajet est hors fenetre (d une heure avant le depart jusqu a la fin). 120 positions par minute et par conducteur.")
    @PostMapping("/api/v1/trips/{id}/live/positions")
    public ResponseEntity<Void> postPosition(@PathVariable UUID id, @Valid @RequestBody LivePositionRequest req) {
        tripLiveService.recordPosition(id, currentUser.id(), req);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Derniere position du vehicule", description = "Conducteur, ou passager avec une reservation CONFIRMED / COMPLETED / PENDING_DRIVER_APPROVAL sur ce trajet (403 sinon).")
    @GetMapping("/api/v1/trips/{id}/live")
    public LivePositionResponse getLive(@PathVariable UUID id) {
        return tripLiveService.getLive(id, currentUser.id());
    }

    @Operation(summary = "Suivi public par jeton", description = "Sans compte. 404 si le jeton est inconnu, le partage coupe, ou le trajet termine / annule depuis plus de 6 h. Aucune donnee personnelle au-dela du prenom du conducteur.")
    @GetMapping("/api/v1/live/{token}")
    public PublicLiveResponse getPublic(@PathVariable @Size(max = 64) String token) {
        return tripLiveService.getPublic(token);
    }
}
