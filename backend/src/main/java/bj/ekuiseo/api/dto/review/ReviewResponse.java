package bj.ekuiseo.api.dto.review;

import bj.ekuiseo.api.domain.enums.ReviewRole;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        UUID tripId,
        UUID authorId,
        UUID targetId,
        ReviewRole role,
        short rating,
        String comment,
        Instant createdAt
) {
}
