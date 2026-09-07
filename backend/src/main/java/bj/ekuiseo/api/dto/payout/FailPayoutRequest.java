package bj.ekuiseo.api.dto.payout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/payouts/{id}/fail : le virement n a pas abouti, avec motif obligatoire. */
public record FailPayoutRequest(
        @NotBlank(message = "Le motif de l echec est obligatoire") @Size(max = 500) String reason
) {
}
