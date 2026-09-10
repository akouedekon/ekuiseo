package bj.ekuiseo.api.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/refunds/{id}/fail : abandon definitif d un remboursement, motif obligatoire. */
public record FailRefundRequest(
        @NotBlank(message = "Le motif est obligatoire") @Size(max = 500) String reason
) {
}
