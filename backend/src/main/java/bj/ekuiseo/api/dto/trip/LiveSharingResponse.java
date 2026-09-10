package bj.ekuiseo.api.dto.trip;

import java.time.Instant;

/**
 * Etat du partage de position vu par le conducteur (PUT /api/v1/trips/{id}/live, V23).
 * {@code sharePath} est le chemin du lien public a partager ({@code /live/{token}}) ;
 * l origine est celle du site. {@code intervalSeconds} (V28) est la cadence d envoi
 * recommandee a l appareil du conducteur.
 */
public record LiveSharingResponse(
        boolean enabled,
        String shareToken,
        String sharePath,
        Instant lastPositionAt,
        int intervalSeconds
) {
}
