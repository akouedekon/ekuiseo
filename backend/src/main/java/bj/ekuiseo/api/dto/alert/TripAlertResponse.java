package bj.ekuiseo.api.dto.alert;

import bj.ekuiseo.api.domain.enums.TripType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TripAlertResponse(
        UUID id,
        String originLabel,
        double originLat,
        double originLng,
        String destLabel,
        double destLat,
        double destLng,
        LocalDate date,
        int seats,
        TripType tripType,
        Instant createdAt,
        boolean active
) {
}
