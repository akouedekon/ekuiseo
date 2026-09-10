package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByKeyAndUserIdAndRoute(String key, UUID userId, String route);

    /** Purge des cles de plus de 24 h (PaymentHousekeepingScheduler). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from IdempotencyKey k where k.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") Instant before);
}
