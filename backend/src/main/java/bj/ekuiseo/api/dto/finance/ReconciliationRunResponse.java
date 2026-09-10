package bj.ekuiseo.api.dto.finance;

import bj.ekuiseo.api.domain.ReconciliationRun;

import java.time.Instant;
import java.util.UUID;

/** Execution d un rapprochement (contrat A.7). */
public record ReconciliationRunResponse(
        UUID id,
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int checked,
        int anomaliesFound,
        String notes
) {
    public static ReconciliationRunResponse from(ReconciliationRun r) {
        return new ReconciliationRunResponse(r.getId(), r.getTrigger().name(), r.getStartedAt(), r.getFinishedAt(),
                r.getStatus().name(), r.getChecked(), r.getAnomaliesFound(), r.getNotes());
    }
}
