package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record StopRequest(
        @NotBlank String label,
        @NotNull Double lat,
        @NotNull Double lng,
        Instant plannedAt,
        @PositiveOrZero long priceFromOrigin
) {
}
