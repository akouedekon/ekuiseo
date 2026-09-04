package bj.ekuiseo.api.dto.conversation;

import java.time.Instant;
import java.util.UUID;

/** Une ligne par conversation (= par reservation), pour GET /api/v1/me/conversations. */
public record ConversationSummary(
        UUID bookingId,
        UUID tripId,
        CounterpartRef counterpart,
        String originLabel,
        String destLabel,
        Instant departureAt,
        String lastMessage,
        Instant lastMessageAt,
        long unreadCount
) {
    public record CounterpartRef(UUID id, String firstName, String lastName, String photoUrl) {
    }
}
