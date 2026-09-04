package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddPaymentMethodRequest(
        @NotNull MobileMoneyOperator provider,
        @NotBlank String phone,
        String label
) {
}
