package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.ReconciliationStatus;
import bj.ekuiseo.api.domain.enums.ReconciliationTrigger;
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

import java.time.Instant;
import java.util.UUID;

/** Execution d un rapprochement Ekuiseo / agregateur (V26, contrat A.7). */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    @Id
    @GeneratedValue
    private UUID id;

    /** Colonne trigger_type : « trigger » est un mot-cle SQL ; expose « trigger » dans l API. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private ReconciliationTrigger trigger;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ReconciliationStatus status = ReconciliationStatus.RUNNING;

    @Column(nullable = false)
    @Builder.Default
    private int checked = 0;

    @Column(name = "anomalies_found", nullable = false)
    @Builder.Default
    private int anomaliesFound = 0;

    @Column(columnDefinition = "text")
    private String notes;

    /** Administrateur a l origine d un rapprochement MANUAL ou IMPORT ; null pour le planificateur. */
    @Column(name = "started_by")
    private UUID startedBy;
}
