package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Etape 1 du changement d adresse : POST /api/v1/me/email/request. */
public record EmailChangeRequest(
        @NotBlank(message = "Indiquez la nouvelle adresse e-mail") @Email @Size(max = 255) String email
) {
}
