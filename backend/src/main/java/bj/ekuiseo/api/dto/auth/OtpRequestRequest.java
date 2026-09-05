package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record OtpRequestRequest(
        @NotBlank(message = "Indiquez un numero de telephone") String phone
) {
}
