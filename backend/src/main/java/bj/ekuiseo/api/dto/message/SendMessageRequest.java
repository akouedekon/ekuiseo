package bj.ekuiseo.api.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps borne a 2 000 caracteres (contrainte chk_messages_body_len, migration V15). */
public record SendMessageRequest(
        @NotBlank @Size(max = 2000) String body
) {
}
