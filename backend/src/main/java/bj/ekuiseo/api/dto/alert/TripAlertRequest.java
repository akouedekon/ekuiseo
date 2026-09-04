package bj.ekuiseo.api.dto.alert;

import bj.ekuiseo.api.domain.enums.TripType;
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
        /** Date ciblee (jour unique) ; null = toute date. Stockee comme une fenetre
         * d'un seul jour (dateFrom = dateTo = date), voir TripAlertService. */
        LocalDate date,
        @Min(1) @Max(8) int seats,
        @NotNull TripType tripType
) {
}
