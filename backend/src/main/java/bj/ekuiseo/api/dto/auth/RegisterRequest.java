package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Format E.164 attendu, ex: +22997000000") String phone,
        @NotBlank String firstName,
        @NotBlank String lastName,
        String email,
        @NotBlank @Size(min = 6, message = "Le mot de passe doit contenir au moins 6 caracteres") String password
) {
}
