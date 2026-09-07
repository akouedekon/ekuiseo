package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByBookingId(UUID bookingId);

    /**
     * Toutes les conversations ou l'utilisateur est participant, cote passager OU
     * cote conducteur du trajet reserve (GET /api/v1/me/conversations). Charge en
     * anticipe la reservation, le trajet, le passager et le conducteur pour eviter
     * un N+1 lors de la construction de ConversationSummary.
     */
    @Query("select c from Conversation c "
            + "join fetch c.booking b join fetch b.trip t join fetch b.passenger join fetch t.driver "
            + "where b.passenger.id = :userId or t.driver.id = :userId "
            + "order by c.createdAt desc")
    List<Conversation> findAllForParticipant(@Param("userId") UUID userId);

    /** Retention (RetentionScheduler) : conversations sans message d un trajet parti avant la date. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            delete from conversations c
            using bookings b
            join trips t on t.id = b.trip_id
            where b.id = c.booking_id
              and t.departure_at < :before
              and not exists (select 1 from messages m where m.conversation_id = c.id)
            """, nativeQuery = true)
    int deleteEmptyForTripsDepartedBefore(@Param("before") java.time.Instant before);
}
