package bj.ekuiseo.api.dto.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entree du journal d audit (GET /api/v1/admin/audit). {@code actorName} (phase 2,
 * constat F313) est le prenom et le nom de l acteur au moment de la lecture, ou null pour
 * une action du systeme (scheduler) ou un compte supprime.
 */
public record AuditLogResponse(
        UUID id,
        UUID actorId,
        String actorName,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> details,
        Instant createdAt
) {
    public AuditLogResponse withActorName(String name) {
        return new AuditLogResponse(id, actorId, name, action, entityType, entityId, details, createdAt);
    }
}
