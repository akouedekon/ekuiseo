package bj.ekuiseo.api.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record SuspendUserRequest(
        @NotBlank String reason
) {
}
