package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    /** Abonnements d un utilisateur, du plus ancien au plus recent (eviction du plus ancien au-dela du plafond). */
    List<PushSubscription> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** L endpoint est unique : un navigateur ne porte qu un abonnement, quel que soit le compte connecte. */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    long countByUserId(UUID userId);

    /** Desabonnement explicite (DELETE /me/push-subscriptions) : seul le proprietaire peut retirer son endpoint. */
    int deleteByUserIdAndEndpoint(UUID userId, String endpoint);

    /** Anonymisation d un compte (UserService#anonymize). */
    int deleteByUserId(UUID userId);
}
