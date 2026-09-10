package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Remboursement d un paiement (V26, contrat A.3), objet de la machine d etat de
 * {@link bj.ekuiseo.api.service.RefundService}. Un seul remboursement « vivant » par paiement
 * (index unique partiel {@code uq_refunds_live_payment} : tout statut sauf FAILED).
 * {@code payments.status} est maintenu en synchronisation (REFUND_PENDING / REFUND_MANUAL /
 * REFUNDED) pour le front et les KPI existants.
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** Reservation concernee ; null pour le paiement d un abonnement. */
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "amount_fcfa", nullable = false)
    private long amountFcfa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RefundKind kind;

    /** ANNULATION_PASSAGER, ANNULATION_CONDUCTEUR, PAYMENT_ORPHAN, AMOUNT_INSUFFICIENT, CONDUCTEUR_ABSENT... */
    @Column(nullable = false, length = 60)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Reference renvoyee par l agregateur, ou saisie par l administrateur au marquage manuel. */
    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    /** Administrateur a l origine de la demande ; null = decision du systeme. */
    @Column(name = "requested_by")
    private UUID requestedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Fin de vie : succes, ou echec definitif decide par un administrateur. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Vrai tant que le remboursement peut encore aboutir (en attente, en cours, en reprise ou en examen). */
    public boolean isAlive() {
        return status == RefundStatus.REQUESTED || status == RefundStatus.PROCESSING
                || status == RefundStatus.MANUAL_REVIEW || (status == RefundStatus.FAILED && completedAt == null);
    }
}
