package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.exception.UnprocessableEntityException;
import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.alert.TripAlertResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.TripAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

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

    @Operation(summary = "Creer une alerte de recherche", description = "Une alerte identique deja active (memes points a 100 m pres, meme date, type et places) est renvoyee telle quelle. 422 au-dela de 10 alertes actives. Sans date, l alerte court 30 jours. Le rayon (radiusKm, 15 par defaut) est celui de la recherche.")
    @PostMapping
    public ResponseEntity<TripAlertResponse> create(@Valid @RequestBody TripAlertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripAlertService.create(currentUser.id(), req));
    }

    @Operation(summary = "Mes alertes", description = "Toutes mes alertes, les plus recentes d abord (actives ou non).")
    @GetMapping
    public List<TripAlertResponse> list() {
        return tripAlertService.list(currentUser.id());
    }

    @Operation(summary = "Supprimer une alerte", description = "Reserve a son proprietaire (403 sinon). 204.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tripAlertService.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 422 au format RFC 7807, coherent avec GlobalExceptionHandler (qui ne connait pas encore
     * cette exception : un gestionnaire local prime sur le @RestControllerAdvice).
     */
    @ExceptionHandler(UnprocessableEntityException.class)
    public ProblemDetail handleUnprocessable(UnprocessableEntityException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://ekuiseo.bj/problems/unprocessable-entity"));
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }
}
