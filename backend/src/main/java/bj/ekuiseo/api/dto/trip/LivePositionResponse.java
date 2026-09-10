package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;

import java.time.Instant;

/**
 * Derniere position du vehicule, pour le conducteur et ses passagers confirmes
 * (GET /api/v1/trips/{id}/live, V23). {@code position} est null tant qu aucune position
 * n a ete recue ; {@code staleSeconds} est l age de cette position a l instant de la
 * reponse (null sans position) - le front la declare perimee au-dela de 90 s.
 * {@code shareToken} n est renseigne que pour le conducteur et les passagers, qui peuvent
 * transmettre le lien public a un proche.
 */
public record LivePositionResponse(
        boolean enabled,
        Position position,
        Long staleSeconds,
        TripStatus tripStatus,
        Instant departureAt,
        String shareToken
) {
    public record Position(
            double lat,
            double lng,
            Float heading,
            Float speedKmh,
            Float accuracyM,
            Instant recordedAt
    ) {
    }
}
