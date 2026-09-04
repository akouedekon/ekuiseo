package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/v1/bookings/{id}/payments/deposit. L'operateur/numero indiques ne
 * servent qu'a pre-remplir le canal du paiement avant confirmation par le
 * webhook Kkiapay (voir PaymentService#initiateDeposit) : le paiement lui-meme
 * reste initie par le widget Kkiapay cote frontend, jamais par ce backend.
 */
public record InitiateDepositRequest(
        @NotNull MobileMoneyOperator provider,
        @NotBlank String phone
) {
}
