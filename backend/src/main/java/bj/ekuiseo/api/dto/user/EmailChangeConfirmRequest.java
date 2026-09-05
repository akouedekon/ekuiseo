package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Etape 2 du changement d adresse : POST /api/v1/me/email/confirm. */
public record EmailChangeConfirmRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Le code comporte 6 chiffres") String code
) {
}
