package bj.ekuiseo.api.dto.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> details,
        Instant createdAt
) {
}
