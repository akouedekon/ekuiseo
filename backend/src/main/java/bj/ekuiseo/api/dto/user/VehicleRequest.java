package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ComfortLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VehicleRequest(
        @NotBlank String brand,
        @NotBlank String model,
        String color,
        @NotBlank String plate,
        @Min(1) @Max(8) int seats,
        @NotNull ComfortLevel comfortLevel,
        String photoUrl
) {
}
