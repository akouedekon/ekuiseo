package bj.ekuiseo.api.dto.finance;

import bj.ekuiseo.api.domain.enums.LedgerAccount;
import bj.ekuiseo.api.domain.enums.LedgerDirection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * POST /api/v1/admin/finance/ledger/adjustments (contrat A.4) : correction d administration,
 * description obligatoire. Seul cas ou un montant vient du client, et il est reserve a ROLE_ADMIN
 * et journalise.
 */
public record LedgerAdjustmentRequest(
        UUID bookingId,
        UUID userId,
        @NotNull LedgerAccount account,
        @NotNull LedgerDirection direction,
        @Min(value = 1, message = "Le montant doit etre strictement positif")
        @Max(value = 100_000_000, message = "Montant hors bornes") long amountFcfa,
        @NotBlank(message = "La description est obligatoire") @Size(max = 500) String description
) {
}
