package bj.ekuiseo.api.dto.push;

/** GET /api/v1/push/vapid-public-key : cle publique VAPID (base64url) a passer a {@code pushManager.subscribe}. */
public record VapidPublicKeyResponse(String publicKey) {
}
