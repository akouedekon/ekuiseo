package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/v1/me/terms : acceptation de la version en vigueur des conditions d utilisation (constat F509). */
public record AcceptTermsRequest(
        @NotBlank(message = "La version des conditions d utilisation acceptee est obligatoire") @Size(max = 20) String termsVersion
) {
}
