package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.AnomalyKind;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Ecart constate par un rapprochement (V26, contrat A.7), a traiter par le back-office. */
@Entity
@Table(name = "reconciliation_anomalies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationAnomaly {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnomalyKind kind;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "provider_tx_id", length = 100)
    private String providerTxId;

    /** Ce qu Ekuiseo attendait (statut, montant, ecritures). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> expected;

    /** Ce que l agregateur (ou le registre) a repondu. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> observed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AnomalyStatus status = AnomalyStatus.OPEN;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
