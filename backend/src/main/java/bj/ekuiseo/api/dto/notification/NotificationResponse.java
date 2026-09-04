package bj.ekuiseo.api.dto.notification;

import bj.ekuiseo.api.domain.enums.NotificationType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        Map<String, Object> payload,
        Instant readAt,
        Instant createdAt
) {
}
