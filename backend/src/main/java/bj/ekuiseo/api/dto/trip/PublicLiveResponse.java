package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;

import java.time.Instant;

/**
 * Suivi public par jeton (GET /api/v1/live/{token}, sans compte, V23) : ce qu un proche du
 * passager a besoin de voir, et rien de plus. Aucune donnee personnelle au-dela du prenom
 * du conducteur ; ni plaque, ni telephone, ni identifiant de trajet ou d utilisateur.
 */
public record PublicLiveResponse(
        String originLabel,
        double originLat,
        double originLng,
        String destLabel,
        double destLat,
        double destLng,
        Instant departureAt,
        TripStatus tripStatus,
        String driverFirstName,
        Vehicle vehicle,
        LivePositionResponse.Position position,
        Long staleSeconds
) {
    public record Vehicle(String brand, String model, String color) {
    }
}
