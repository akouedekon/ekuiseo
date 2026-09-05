package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record StopRequest(
        @NotBlank String label,
        @NotNull Double lat,
        @NotNull Double lng,
        Instant plannedAt,
        @Positive(message = "Le prix depuis le depart doit etre superieur a 0 F") long priceFromOrigin
) {
}
