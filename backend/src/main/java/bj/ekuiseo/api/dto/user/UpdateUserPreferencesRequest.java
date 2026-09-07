package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ChattyLevel;
import jakarta.validation.constraints.Size;

/** PATCH partiel : tout champ absent (null) est laisse inchange. {@code language} est borne comme la colonne (VARCHAR(5), V6). */
public record UpdateUserPreferencesRequest(
        Boolean notifyByPush,
        Boolean notifyBySms,
        Boolean notifyByEmail,
        @Size(max = 5) String language,
        Boolean smoking,
        Boolean music,
        Boolean pets,
        ChattyLevel chatty
) {
}
