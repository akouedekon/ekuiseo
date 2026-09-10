package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.trip.LivePositionAck;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingRequest;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.TripLiveService;
import bj.ekuiseo.api.service.live.LocationUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Suivi en direct d un trajet (V23, V28) : partage de position par le conducteur et ses
 * passagers confirmes, lecture par instantane ou par flux SSE, lien public a jeton. Les
 * routes {@code /api/v1/trips/{id}/live*} sont authentifiees ; {@code GET /api/v1/live/{token}}
 * est publique (SecurityConfig) et bornee par IP (RateLimitingFilter, quota live-public).
 */
@Tag(name = "Suivi en direct", description = "Position du vehicule et des passagers pendant le trajet : partage, instantane, flux SSE, lien public a jeton")
@RestController
@Validated
public class TripLiveController {

    private final TripLiveService tripLiveService;
    private final LocationUpdateService locationUpdateService;
    private final CurrentUser currentUser;

    public TripLiveController(TripLiveService tripLiveService, LocationUpdateService locationUpdateService,
                              CurrentUser currentUser) {
        this.tripLiveService = tripLiveService;
        this.locationUpdateService = locationUpdateService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Activer ou couper le partage de position", description = "Reserve au conducteur, trajet PUBLISHED / FULL / ONGOING. Le jeton du lien public est genere a la premiere activation et conserve tant que le trajet vit. Couper le partage ferme les flux ouverts.")
    @PutMapping("/api/v1/trips/{id}/live")
    public LiveSharingResponse setSharing(@PathVariable UUID id, @Valid @RequestBody LiveSharingRequest req) {
        return tripLiveService.setSharing(id, currentUser.id(), req.enabled());
    }

    @Operation(summary = "Envoyer une position", description = "Conducteur (partage active) ou passager avec une reservation CONFIRMED ; 403 sinon, 400 hors fenetre (d une heure avant le depart jusqu a la fin), 429 a moins de 2 s de la precedente. Reponse : acceptee ou non, flags releves (OUT_OF_AREA, CLOCK_SKEW, TELEPORT, LOW_ACCURACY), cadence recommandee.")
    @PostMapping("/api/v1/trips/{id}/live/positions")
    public LivePositionAck postPosition(@PathVariable UUID id, @Valid @RequestBody LivePositionRequest req) {
        return locationUpdateService.record(id, currentUser.id(), req);
    }

    @Operation(summary = "Instantane du suivi", description = "Conducteur, ou passager avec une reservation CONFIRMED / COMPLETED / PENDING_DRIVER_APPROVAL sur ce trajet (403 sinon) : derniere position du conducteur, participants visibles selon le role, cadence recommandee.")
    @GetMapping("/api/v1/trips/{id}/live")
    public LivePositionResponse getLive(@PathVariable UUID id) {
        return tripLiveService.getLive(id, currentUser.id());
    }

    @Operation(summary = "Flux du suivi (Server-Sent Events)", description = "Memes droits que l instantane. Evenements snapshot, position, status, ping (20 s) et end (fin du trajet, annulation, partage coupe). A ouvrir avec fetch et l en-tete Authorization, jamais avec un jeton dans l URL.")
    @GetMapping(value = "/api/v1/trips/{id}/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable UUID id) {
        SseEmitter emitter = tripLiveService.stream(id, currentUser.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                // nginx / Caddy : ne pas mettre le flux en tampon, chaque evenement doit partir aussitot.
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    @Operation(summary = "Suivi public par jeton", description = "Sans compte. 404 si le jeton est inconnu, le partage coupe, ou le trajet termine / annule depuis plus de 6 h. Conducteur seulement, aucune donnee personnelle au-dela de son prenom.")
    @GetMapping("/api/v1/live/{token}")
    public PublicLiveResponse getPublic(@PathVariable @Size(max = 64) String token) {
        return tripLiveService.getPublic(token);
    }
}
