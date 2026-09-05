package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;

import java.util.UUID;

/** Compte mobile money du profil ; {@code verified} = possession du numero etablie (seul un compte verifie recoit des reversements). */
public record PaymentMethodResponse(
        UUID id,
        MobileMoneyOperator provider,
        String phone,
        String label,
        boolean isDefault,
        boolean verified
) {
}
