package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.LiveRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Derniere position connue d un participant au suivi en direct (contrat C, V28) : le
 * conducteur ({@code bookingId} null) ou un passager confirme (sa reservation). Seul le
 * prenom est expose. {@code flags} liste les anomalies relevees par le serveur sur cette
 * position (ex. LOW_ACCURACY) ; une position OUT_OF_AREA ou TELEPORT n est jamais diffusee.
 */
public record LiveParticipant(
        LiveRole role,
        UUID bookingId,
        String firstName,
        double lat,
        double lng,
        Float heading,
        Float speedKmh,
        Float accuracyM,
        Instant recordedAt,
        List<String> flags
) {
}
