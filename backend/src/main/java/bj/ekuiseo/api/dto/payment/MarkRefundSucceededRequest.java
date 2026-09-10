package bj.ekuiseo.api.dto.payment;

import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/refunds/{id}/mark-succeeded : reference du remboursement fait a la main (tableau de bord de l agregateur, virement). */
public record MarkRefundSucceededRequest(
        @Size(max = 100) String providerReference
) {
}
