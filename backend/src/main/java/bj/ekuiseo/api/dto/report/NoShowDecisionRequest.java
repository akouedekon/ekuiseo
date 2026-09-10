package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.NoShowResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/admin/reports/{id}/no-show-decision (V25) : la moderation tranche un dossier
 * « conducteur absent » lie a une reservation. La note est conservee sur le signalement et
 * transmise a l auteur (comme toute cloture).
 */
public record NoShowDecisionRequest(
        @NotNull NoShowResolution decision,
        @NotBlank(message = "Une note de decision est obligatoire") @Size(max = 500) String note
) {
}
