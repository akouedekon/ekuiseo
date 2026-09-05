package bj.ekuiseo.api.dto.payout;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;

import java.time.Instant;
import java.util.UUID;

/** Compte mobile money vu du back-office (GET /api/v1/admin/payment-accounts) : a verifier avant de servir de destination de reversement. */
public record AdminPaymentAccountResponse(
        UUID id,
        UUID userId,
        String userName,
        String userPhone,
        MobileMoneyOperator provider,
        String phone,
        String label,
        boolean isDefault,
        Instant verifiedAt,
        Instant createdAt
) {
}
