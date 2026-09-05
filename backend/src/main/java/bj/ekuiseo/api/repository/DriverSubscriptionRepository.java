package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverSubscriptionRepository extends JpaRepository<DriverSubscription, UUID> {

    List<DriverSubscription> findByDriverIdOrderByCreatedAtDesc(UUID driverId);

    @Query("select s from DriverSubscription s where s.driver.id = :driverId and s.status = "
            + "bj.ekuiseo.api.domain.enums.SubscriptionStatus.ACTIVE and s.currentPeriodEnd >= :now")
    Optional<DriverSubscription> findActive(@Param("driverId") UUID driverId, @Param("now") Instant now);

    default boolean hasActiveSubscription(UUID driverId, Instant now) {
        return findActive(driverId, now).isPresent();
    }

    Optional<DriverSubscription> findFirstByDriverIdAndStatusOrderByCreatedAtDesc(UUID driverId, SubscriptionStatus status);

    /** Abonnements jamais payes (PENDING_PAYMENT) plus anciens que before, a expirer. */
    List<DriverSubscription> findByStatusAndCreatedAtBefore(SubscriptionStatus status, Instant before);
}
