package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inscription sans mot de passe (parcours OTP, le seul propose par l'interface) :
 * le compte est cree puis un code est envoye a l adresse e-mail (obligatoire : c est la que partent les codes de connexion) ; la session ne s'ouvre qu'a la
 * verification du code (POST /auth/otp/verify), comme pour une connexion.
 *
 * <p>{@code acceptTerms} et {@code termsVersion} (constat F509) : l acceptation des
 * conditions d utilisation est obligatoire et horodatee avec la version acceptee, qui doit
 * etre celle en vigueur ({@code ekuiseo.terms.version}, 400 sinon).</p>
 */
public record OtpRegisterRequest(
        @NotBlank(message = "Indiquez un numero de telephone") String phone,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank(message = "L adresse e-mail est obligatoire : le code de connexion y est envoye") @Email @Size(max = 160) String email,
        @AssertTrue(message = "Vous devez accepter les conditions d utilisation pour creer un compte") boolean acceptTerms,
        @NotBlank(message = "La version des conditions d utilisation acceptee est obligatoire") @Size(max = 20) String termsVersion
) {
}
