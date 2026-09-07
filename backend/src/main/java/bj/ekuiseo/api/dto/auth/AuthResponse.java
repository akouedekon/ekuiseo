package bj.ekuiseo.api.dto.auth;

import bj.ekuiseo.api.dto.user.UserResponse;

/**
 * Session ouverte. Entre les services, {@code refreshToken} porte le jeton emis ; sur le
 * fil, {@code AuthController} le deplace dans le cookie HttpOnly {@code ekuiseo_refresh}
 * et renvoie {@link #withoutRefreshToken()} : le champ reste dans le contrat (compatibilite
 * des clients) mais vaut null, donc absent du JSON (constats F355/F405).
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {

    /** Meme session sans le jeton de rafraichissement, forme exposee au client. */
    public AuthResponse withoutRefreshToken() {
        return new AuthResponse(accessToken, null, user);
    }
}
