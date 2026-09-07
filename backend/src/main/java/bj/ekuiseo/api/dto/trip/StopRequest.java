package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record StopRequest(
        @NotBlank @Size(max = 255) String label,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        Instant plannedAt,
        @Positive(message = "Le prix depuis le depart doit etre superieur a 0 F") long priceFromOrigin
) {
}
