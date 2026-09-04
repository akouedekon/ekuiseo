package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ChattyLevel;

public record UserPreferencesResponse(
        boolean notifyByPush,
        boolean notifyBySms,
        boolean notifyByEmail,
        String language,
        boolean smoking,
        boolean music,
        boolean pets,
        ChattyLevel chatty
) {
}
