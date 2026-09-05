package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record OtpVerifyRequest(
        @NotBlank(message = "Indiquez un numero de telephone") String phone,
        @NotBlank String code
) {
}
