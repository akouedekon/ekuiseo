package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Remboursements a traiter (GET /api/v1/admin/overview). */
    long countByStatusIn(List<PaymentStatus> statuses);

    /**
     * Paiements d un utilisateur : ceux de ses reservations (passager) et de ses abonnements
     * (conducteur), plus recents d abord. Fiche admin (GET /api/v1/admin/users/{id}/payments)
     * et export des donnees personnelles (UserDataExportService).
     */
    @Query(value = "select p from Payment p left join p.booking b left join p.subscription s "
            + "where b.passenger.id = :userId or s.driver.id = :userId order by p.createdAt desc",
            countQuery = "select count(p) from Payment p left join p.booking b left join p.subscription s "
                    + "where b.passenger.id = :userId or s.driver.id = :userId")
    Page<Payment> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    /** Variante non paginee de {@link #findByUserId(UUID, Pageable)}, pour l export. */
    @Query("select p from Payment p left join p.booking b left join p.subscription s "
            + "where b.passenger.id = :userId or s.driver.id = :userId order by p.createdAt asc")
    List<Payment> findAllByUserId(@Param("userId") UUID userId);
}
