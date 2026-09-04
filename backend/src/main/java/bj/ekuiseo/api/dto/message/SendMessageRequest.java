package bj.ekuiseo.api.dto.message;

import jakarta.validation.constraints.NotBlank;

public record SendMessageRequest(
        @NotBlank String body
) {
}
