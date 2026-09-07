package bj.ekuiseo.api.dto.alert;

import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record TripAlertRequest(
        @NotBlank String originLabel,
        double originLat,
        double originLng,
        @NotBlank String destLabel,
        double destLat,
        double destLng,
        /** Date ciblee (jour unique) ; null = toute date pendant 30 jours. Stockee comme une
         * fenetre d'un seul jour (dateFrom = dateTo = date), voir TripAlertService. */
        LocalDate date,
        @Min(1) @Max(8) int seats,
        @NotNull TripType tripType,
        /** Rayon de correspondance (km), celui de la recherche d origine ; 15 km par defaut (V16, constat F530). */
        @DecimalMin("1.0") @DecimalMax("50.0") Double radiusKm
) {
}
