package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inscription sans mot de passe (parcours OTP, le seul propose par l'interface) :
 * le compte est cree puis un code est envoye a l adresse e-mail (obligatoire : c est la que partent les codes de connexion) ; la session ne s'ouvre qu'a la
 * verification du code (POST /auth/otp/verify), comme pour une connexion.
 */
public record OtpRegisterRequest(
        @NotBlank(message = "Indiquez un numero de telephone") String phone,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank(message = "L adresse e-mail est obligatoire : le code de connexion y est envoye") @Email @Size(max = 160) String email
) {
}
