package bj.ekuiseo.api.dto.payout;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Corps optionnel de POST /api/v1/admin/payouts/{id}/settle : reference du virement mobile
 * money et montant effectivement vire (par defaut le montant du lot). Consignes sur le lot
 * (V16) et dans l audit PAYOUT_SETTLED.
 */
public record SettlePayoutRequest(
        @Size(max = 100) String externalReference,
        @PositiveOrZero Long settledAmountFcfa
) {
}
