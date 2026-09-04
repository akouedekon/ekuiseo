package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.alert.TripAlertResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.TripAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alertes de recherche (regle metier n.13) : prevenir le passager quand une offre correspondante est publiee. */
@Tag(name = "Alertes de trajet", description = "Etre prevenu quand un trajet correspondant a une recherche est publie")
@RestController
@RequestMapping("/api/v1/trip-alerts")
public class TripAlertController {

    private final TripAlertService tripAlertService;
    private final CurrentUser currentUser;

    public TripAlertController(TripAlertService tripAlertService, CurrentUser currentUser) {
        this.tripAlertService = tripAlertService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Creer une alerte de recherche")
    @PostMapping
    public ResponseEntity<TripAlertResponse> create(@Valid @RequestBody TripAlertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripAlertService.create(currentUser.id(), req));
    }
}
