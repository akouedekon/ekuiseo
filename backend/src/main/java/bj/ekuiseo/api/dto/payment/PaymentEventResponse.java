package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.PaymentEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Evenement de paiement (contrat A.5), GET /api/v1/admin/payments/{id}/events. */
public record PaymentEventResponse(
        UUID id,
        Instant createdAt,
        String eventType,
        String fromStatus,
        String toStatus,
        String source,
        UUID actorId,
        Map<String, Object> details
) {
    public static PaymentEventResponse from(PaymentEvent e) {
        return new PaymentEventResponse(e.getId(), e.getCreatedAt(), e.getEventType(),
                e.getFromStatus() == null ? null : e.getFromStatus().name(),
                e.getToStatus() == null ? null : e.getToStatus().name(),
                e.getSource().name(), e.getActorId(), e.getDetails());
    }
}
