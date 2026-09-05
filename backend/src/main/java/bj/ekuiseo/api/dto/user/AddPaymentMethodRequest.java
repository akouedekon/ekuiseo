package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Numero normalise en E.164 par PaymentAccountService (PhoneNumbers) : espaces et format local acceptes. */
public record AddPaymentMethodRequest(
        @NotNull MobileMoneyOperator provider,
        @NotBlank(message = "Indiquez le numero du compte") @Size(max = 25) String phone,
        @Size(max = 100) String label
) {
}
