package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Marque en masse toutes les notifications non lues d'un utilisateur (POST /api/v1/notifications/read-all). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.user.id = :userId and n.readAt is null")
    int markAllAsRead(@Param("userId") UUID userId, @Param("now") Instant now);
}
