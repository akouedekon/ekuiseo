package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.PaymentWebhookEvent;
import bj.ekuiseo.api.domain.enums.WebhookOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {

    /** Ligne « maitresse » d un corps donne (jamais une ligne DUPLICATE). */
    Optional<PaymentWebhookEvent> findFirstByProviderAndPayloadHashAndOutcomeNot(String provider, String payloadHash,
                                                                                 WebhookOutcome outcome);

    Page<PaymentWebhookEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
