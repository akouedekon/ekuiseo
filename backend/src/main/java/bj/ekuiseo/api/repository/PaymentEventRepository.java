package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<PaymentEvent> findByPaymentIdInOrderByCreatedAtAsc(List<UUID> paymentIds);
}
