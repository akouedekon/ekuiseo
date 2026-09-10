package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /** Remboursement charge sous verrou pessimiste : une seule execution a la fois. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    /** Remboursement vivant d un paiement (tout statut sauf FAILED definitif), le plus recent. */
    @Query("select r from Refund r where r.payment.id = :paymentId "
            + "and (r.status <> bj.ekuiseo.api.domain.enums.RefundStatus.FAILED or r.completedAt is null) "
            + "order by r.createdAt desc")
    List<Refund> findLiveByPaymentId(@Param("paymentId") UUID paymentId);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<Refund> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    /**
     * Remboursements a (re)tenter par le planificateur : demandes jamais executees (REQUESTED
     * depuis plus de {@code before}, ex. redemarrage entre la decision et l execution), echecs
     * transitoires (FAILED non definitif, sous le plafond de tentatives) et executions restees
     * PROCESSING depuis plus de {@code stale} (arret pendant l appel a l agregateur).
     */
    @Query("select r.id from Refund r where "
            + "(r.status = bj.ekuiseo.api.domain.enums.RefundStatus.REQUESTED and r.createdAt < :before) "
            + "or (r.status = bj.ekuiseo.api.domain.enums.RefundStatus.FAILED and r.completedAt is null "
            + "    and r.attempts < :maxAttempts and r.updatedAt < :before) "
            + "or (r.status = bj.ekuiseo.api.domain.enums.RefundStatus.PROCESSING and r.updatedAt < :stale)")
    List<UUID> findRetryable(@Param("before") Instant before, @Param("stale") Instant stale,
                             @Param("maxAttempts") int maxAttempts);

    /** Vue back-office, plus recents d abord, paiement / reservation / passager charges en une requete. */
    @Query(value = "select r from Refund r join fetch r.payment p left join fetch p.booking b left join fetch b.passenger "
            + "where r.status in :statuses",
            countQuery = "select count(r) from Refund r where r.status in :statuses")
    Page<Refund> findForAdmin(@Param("statuses") List<RefundStatus> statuses, Pageable pageable);

    /** Remboursements a suivre (GET /api/v1/admin/overview, FinanceSummaryResponse.openRefunds). */
    @Query("select count(r) from Refund r where r.status in (bj.ekuiseo.api.domain.enums.RefundStatus.REQUESTED, "
            + "bj.ekuiseo.api.domain.enums.RefundStatus.PROCESSING, bj.ekuiseo.api.domain.enums.RefundStatus.MANUAL_REVIEW) "
            + "or (r.status = bj.ekuiseo.api.domain.enums.RefundStatus.FAILED and r.completedAt is null)")
    long countOpen();

    /** Remboursements des paiements d une liste de reservations (fiche admin). */
    List<Refund> findByBookingIdInOrderByCreatedAtDesc(List<UUID> bookingIds);
}
