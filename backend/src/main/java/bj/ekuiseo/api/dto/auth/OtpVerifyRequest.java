package bj.ekuiseo.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotBlank String code
) {
}
