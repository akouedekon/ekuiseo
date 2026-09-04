package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByProviderAndProviderTxId(PaymentProvider provider, String providerTxId);
    List<Payment> findByBookingId(UUID bookingId);
    Optional<Payment> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, PaymentStatus status);
    List<Payment> findBySubscriptionId(UUID subscriptionId);
}
