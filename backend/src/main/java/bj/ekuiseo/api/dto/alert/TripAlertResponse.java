package bj.ekuiseo.api.dto.alert;

import bj.ekuiseo.api.domain.enums.TripType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Alerte de recherche (POST/GET /api/v1/trip-alerts). {@code date} est la date ciblee
 * (null = toute date) ; {@code activeUntil} la derniere date couverte (une alerte sans
 * date est bornee a 30 jours) ; {@code radiusKm} le rayon de correspondance.
 */
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
        boolean active,
        double radiusKm,
        LocalDate activeUntil
) {
}
