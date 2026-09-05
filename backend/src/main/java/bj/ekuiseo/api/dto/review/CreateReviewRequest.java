package bj.ekuiseo.api.dto.review;

import bj.ekuiseo.api.domain.enums.ReviewRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

/**
 * POST /api/v1/trips/{id}/reviews.
 *
 * @param targetId passager a evaluer quand l auteur est le conducteur du trajet ; ignore
 *                 quand l auteur est un passager (la cible est alors toujours le conducteur)
 * @param role     conserve pour compatibilite avec les clients existants mais IGNORE : le
 *                 role est deduit cote serveur du lien entre l auteur et le trajet (constat F022)
 */
public record CreateReviewRequest(
        UUID targetId,
        ReviewRole role,
        @Min(1) @Max(5) short rating,
        String comment
) {
}
