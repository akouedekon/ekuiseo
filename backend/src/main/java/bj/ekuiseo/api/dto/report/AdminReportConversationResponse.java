package bj.ekuiseo.api.dto.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Conversation liee a un signalement, GET /api/v1/admin/reports/{id}/conversations
 * (constat F549). Chaque consultation est journalisee (ADMIN_CONVERSATION_VIEWED) : ce
 * sont des echanges prives, ouverts a la moderation pour la seule instruction du
 * signalement (docs/CONFORMITE.md).
 */
public record AdminReportConversationResponse(
        UUID conversationId,
        UUID bookingId,
        UUID tripId,
        List<Participant> participants,
        List<MessageItem> messages
) {
    public record Participant(UUID id, String firstName, String lastName) {
    }

    public record MessageItem(UUID id, UUID senderId, String body, Instant createdAt) {
    }
}
