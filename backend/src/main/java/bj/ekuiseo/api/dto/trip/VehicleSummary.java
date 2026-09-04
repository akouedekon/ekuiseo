package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.ComfortLevel;

import java.util.UUID;

public record VehicleSummary(
        UUID id,
        String brand,
        String model,
        String color,
        ComfortLevel comfortLevel
) {
}
