package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByProviderAndProviderTxId(PaymentProvider provider, String providerTxId);
    List<Payment> findByBookingId(UUID bookingId);
    Optional<Payment> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, PaymentStatus status);
    List<Payment> findBySubscriptionId(UUID subscriptionId);

    /** Remboursements a (re)tenter : demandes depuis plus de {@code before} et toujours en attente. */
    List<Payment> findByStatusAndRefundRequestedAtBefore(PaymentStatus status, Instant before);

    /** Vue back-office des paiements a suivre (remboursements en attente ou manuels), plus recents d abord. */
    @Query("select p from Payment p left join fetch p.booking b left join fetch b.passenger "
            + "where p.status in :statuses order by coalesce(p.refundRequestedAt, p.createdAt) desc")
    List<Payment> findForAdmin(@Param("statuses") List<PaymentStatus> statuses);
}
