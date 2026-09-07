package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ComfortLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bornes alignees sur les colonnes de {@code vehicles} (V1) : un depassement donne un 400 explicite, plus un 409 generique. */
public record VehicleRequest(
        @NotBlank @Size(max = 100) String brand,
        @NotBlank @Size(max = 100) String model,
        @Size(max = 50) String color,
        @NotBlank @Size(max = 20) String plate,
        @Min(1) @Max(8) int seats,
        @NotNull ComfortLevel comfortLevel,
        @Size(max = 500) String photoUrl
) {
}
