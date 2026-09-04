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

/** Emission et validation des jetons JWT (access + refresh). */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMinutes;
    private final long refreshTtlDays;

    public JwtService(@Value("${ekuiseo.jwt.secret}") String secret,
                       @Value("${ekuiseo.jwt.access-token-ttl-minutes}") long accessTtlMinutes,
                       @Value("${ekuiseo.jwt.refresh-token-ttl-days}") long refreshTtlDays) {
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

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of("type", "refresh"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtlDays, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
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
}
