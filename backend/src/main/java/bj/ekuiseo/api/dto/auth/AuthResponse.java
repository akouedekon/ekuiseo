package bj.ekuiseo.api.dto.auth;

import bj.ekuiseo.api.dto.user.UserResponse;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {
}
