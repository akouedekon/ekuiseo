package bj.ekuiseo.api.dto.finance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** POST /api/v1/admin/finance/reconciliation/run : nombre de jours a re-verifier (7 par defaut, 90 au plus). */
public record ReconciliationRunRequest(
        @Min(1) @Max(90) Integer days
) {
}
