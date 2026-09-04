package bj.ekuiseo.api.dto.message;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String body,
        Instant readAt,
        Instant createdAt
) {
}
