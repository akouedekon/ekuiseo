package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * POST /api/v1/trips/{id}/live/positions : une position du vehicule envoyee par le
 * navigateur du conducteur (V23). Cap, vitesse et precision sont facultatifs (le
 * navigateur ne les connait pas toujours) ; {@code recordedAt} absent vaut « maintenant ».
 */
public record LivePositionRequest(
        @NotNull(message = "lat est obligatoire")
        @DecimalMin(value = "-90", message = "lat doit etre comprise entre -90 et 90")
        @DecimalMax(value = "90", message = "lat doit etre comprise entre -90 et 90")
        Double lat,
        @NotNull(message = "lng est obligatoire")
        @DecimalMin(value = "-180", message = "lng doit etre comprise entre -180 et 180")
        @DecimalMax(value = "180", message = "lng doit etre comprise entre -180 et 180")
        Double lng,
        @DecimalMin(value = "0", message = "heading doit etre compris entre 0 et 360")
        @DecimalMax(value = "360", message = "heading doit etre compris entre 0 et 360")
        Float heading,
        @PositiveOrZero(message = "speedKmh doit etre positive ou nulle")
        @DecimalMax(value = "300", message = "speedKmh est invraisemblable")
        Float speedKmh,
        @PositiveOrZero(message = "accuracyM doit etre positive ou nulle")
        Float accuracyM,
        Instant recordedAt
) {
}
