package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.VehicleType;

import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String brand,
        String model,
        String color,
        String plate,
        int seats,
        VehicleType vehicleType,
        ComfortLevel comfortLevel,
        String photoUrl,
        boolean verified
) {
}
