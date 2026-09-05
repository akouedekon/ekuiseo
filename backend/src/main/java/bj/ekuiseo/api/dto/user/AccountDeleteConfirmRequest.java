package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Etape 2 de la suppression de compte : POST /api/v1/me/delete. */
public record AccountDeleteConfirmRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Le code comporte 6 chiffres") String code
) {
}
