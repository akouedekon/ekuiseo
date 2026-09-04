package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ChattyLevel;

/** PATCH partiel : tout champ absent (null) est laisse inchange. */
public record UpdateUserPreferencesRequest(
        Boolean notifyByPush,
        Boolean notifyBySms,
        Boolean notifyByEmail,
        String language,
        Boolean smoking,
        Boolean music,
        Boolean pets,
        ChattyLevel chatty
) {
}
