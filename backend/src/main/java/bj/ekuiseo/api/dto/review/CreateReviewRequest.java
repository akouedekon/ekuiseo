package bj.ekuiseo.api.dto.review;

import bj.ekuiseo.api.domain.enums.ReviewRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateReviewRequest(
        @NotNull UUID targetId,
        @NotNull ReviewRole role,
        @Min(1) @Max(5) short rating,
        String comment
) {
}
