package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Le code comporte exactement 6 chiffres (constat F545) : rien d autre ne merite un calcul BCrypt. */
public record OtpVerifyRequest(
        @NotBlank(message = "Indiquez un numero de telephone") String phone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Le code comporte 6 chiffres") String code
) {
}
