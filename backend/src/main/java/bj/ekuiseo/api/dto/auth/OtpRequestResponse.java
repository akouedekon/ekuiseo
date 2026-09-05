package bj.ekuiseo.api.dto.auth;

/**
 * Reponse de POST /auth/otp/request et /auth/otp/register : par quel canal le code est
 * parti ({@code EMAIL} ou {@code SMS}) et vers quelle destination masquee
 * (ex. {@code af***@example.com}), pour que l'interface dise ou regarder.
 */
public record OtpRequestResponse(String channel, String destination) {
}
