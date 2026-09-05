package bj.ekuiseo.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Emission et validation des jetons JWT (access + refresh). Les deux partagent la cle
 * de signature ; la claim {@code type} les distingue, et le refresh porte un {@code jti}
 * enregistre en base (RefreshTokenService : rotation, revocation, duree absolue).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMinutes;
    private final long refreshTtlDays;

    public JwtService(@Value("${ekuiseo.jwt.secret}") String secret,
                       @Value("${ekuiseo.jwt.access-token-ttl-minutes}") long accessTtlMinutes,
                       @Value("${ekuiseo.jwt.refresh-token-ttl-days}") long refreshTtlDays) {
        if (secret == null || secret.isBlank() || secret.startsWith("change-me")) {
            // La valeur d exemple historique de .env.example est publique (depot GitHub) :
            // une cle connue de tous signe des jetons forgeables. Aucun repli n est tolere.
            throw new IllegalStateException(
                    "ekuiseo.jwt.secret (JWT_SECRET) est absente ou vaut encore la valeur d exemple : "
                            + "generez une cle forte, par exemple : openssl rand -base64 48");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 exige une cle d'au moins 256 bits (32 octets). Completer silencieusement
            // une cle trop courte reviendrait a affaiblir la securite sans que personne ne s'en
            // rende compte : on refuse donc de demarrer, avec un message explicite plutot qu'une
            // erreur JJWT obscure plus loin dans la pile.
            throw new IllegalStateException(
                    "ekuiseo.jwt.secret (JWT_SECRET) est trop courte : " + bytes.length
                            + " octets fournis, 32 octets minimum requis pour HS256. "
                            + "Generez une cle forte, par exemple : openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    public String generateAccessToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of("type", "access"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** Refresh token enregistre : {@code jti} = identifiant de la ligne refresh_tokens, expiration fournie par le service. */
    public String generateRefreshToken(UUID userId, UUID jti, Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .id(jti.toString())
                .claims(Map.of("type", "refresh"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** Refresh token non enregistre (tests, outillage) : jti aleatoire, duree glissante par defaut. */
    public String generateRefreshToken(UUID userId) {
        return generateRefreshToken(userId, UUID.randomUUID(), Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS));
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parse(token).getSubject());
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(parse(token).get("type", String.class));
    }

    /**
     * Identifiant du porteur d un jeton d ACCES. Un jeton de rafraichissement (30 jours,
     * non revocable) presente en Authorization: Bearer est refuse : les deux jetons
     * partagent la cle de signature, seule la claim {@code type} les distingue.
     */
    public UUID extractUserIdFromAccessToken(String token) {
        Claims claims = parse(token);
        if (!"access".equals(claims.get("type", String.class))) {
            throw new JwtException("Ce jeton n est pas un jeton d acces");
        }
        return UUID.fromString(claims.getSubject());
    }

    /** Claims d un refresh token : sujet et jti, apres verification de la signature, de l expiration et du type. */
    public RefreshClaims extractRefreshClaims(String token) {
        Claims claims = parse(token);
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new JwtException("Ce jeton n est pas un jeton de rafraichissement");
        }
        if (claims.getId() == null) {
            throw new JwtException("Jeton de rafraichissement sans identifiant (ancien format)");
        }
        return new RefreshClaims(UUID.fromString(claims.getSubject()), UUID.fromString(claims.getId()));
    }

    public record RefreshClaims(UUID userId, UUID jti) {
    }
}
