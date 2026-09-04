package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inscription sans mot de passe (parcours OTP, le seul propose par l'interface) :
 * le compte est cree puis un code SMS est envoye ; la session ne s'ouvre qu'a la
 * verification du code (POST /auth/otp/verify), comme pour une connexion.
 */
public record OtpRegisterRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Format E.164 attendu, ex: +22997000000") String phone,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @Email @Size(max = 160) String email
) {
}
