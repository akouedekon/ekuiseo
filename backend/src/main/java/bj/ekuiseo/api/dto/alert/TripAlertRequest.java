package bj.ekuiseo.api.dto.alert;

import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Validation defensive (constat F531) : coordonnees bornees, libelles alignes sur search_alerts (255), date non passee. */
public record TripAlertRequest(
        @NotBlank @Size(max = 255) String originLabel,
        @DecimalMin("-90") @DecimalMax("90") double originLat,
        @DecimalMin("-180") @DecimalMax("180") double originLng,
        @NotBlank @Size(max = 255) String destLabel,
        @DecimalMin("-90") @DecimalMax("90") double destLat,
        @DecimalMin("-180") @DecimalMax("180") double destLng,
        /** Date ciblee (jour unique) ; null = toute date. Stockee comme une fenetre
         * d'un seul jour (dateFrom = dateTo = date), voir TripAlertService. */
        @FutureOrPresent LocalDate date,
        @Min(1) @Max(8) int seats,
        @NotNull TripType tripType
) {
}
