package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;

import java.util.UUID;

public record PaymentMethodResponse(
        UUID id,
        MobileMoneyOperator provider,
        String phone,
        String label,
        boolean isDefault
) {
}
