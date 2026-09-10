package bj.ekuiseo.api.dto.finance;

import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/finance/reconciliation/anomalies/{id}/resolve : RESOLVED ou IGNORED, note obligatoire. */
public record ResolveAnomalyRequest(
        @NotNull AnomalyStatus status,
        @NotBlank(message = "La note est obligatoire") @Size(max = 2000) String note
) {
}
