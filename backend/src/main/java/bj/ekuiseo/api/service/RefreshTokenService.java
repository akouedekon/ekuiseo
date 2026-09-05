package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.RefreshToken;
import bj.ekuiseo.api.repository.RefreshTokenRepository;
import bj.ekuiseo.api.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Cycle de vie des jetons de rafraichissement (constat F001 de l audit).
 *
 * <ul>
 *   <li><b>Emission</b> : un {@code jti} par jeton, enregistre en base avec sa famille
 *       (la connexion d origine), sa fin de validite glissante (30 jours) et sa fin
 *       absolue (90 jours par defaut).</li>
 *   <li><b>Rotation</b> : chaque {@code /auth/refresh} revoque le jeton presente et en
 *       emet un nouveau dans la meme famille, sans depasser la borne absolue.</li>
 *   <li><b>Reutilisation</b> : un jeton deja revoque presente a nouveau signale un vol
 *       (ou un client qui a perdu la reponse) : toute la famille est revoquee, l
 *       utilisateur se reconnecte.</li>
 *   <li><b>Revocation</b> : a la deconnexion (famille), a la suspension et a la
 *       correction de contact par un administrateur (tous les jetons de l utilisateur).</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final JwtService jwtService;
    private final long slidingTtlDays;
    private final long absoluteTtlDays;

    public RefreshTokenService(RefreshTokenRepository repository, JwtService jwtService,
                               @Value("${ekuiseo.jwt.refresh-token-ttl-days:30}") long slidingTtlDays,
                               @Value("${ekuiseo.jwt.refresh-absolute-ttl-days:90}") long absoluteTtlDays) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.slidingTtlDays = slidingTtlDays;
        this.absoluteTtlDays = absoluteTtlDays;
    }

    /** Nouvelle famille : connexion initiale (verification d un code). */
    @Transactional
    public String issue(UUID userId) {
        Instant now = Instant.now();
        return issue(userId, UUID.randomUUID(), now.plus(absoluteTtlDays, ChronoUnit.DAYS), now);
    }

    private String issue(UUID userId, UUID familyId, Instant absoluteExpiresAt, Instant now) {
        Instant sliding = now.plus(slidingTtlDays, ChronoUnit.DAYS);
        Instant expiresAt = sliding.isBefore(absoluteExpiresAt) ? sliding : absoluteExpiresAt;
        RefreshToken token = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .familyId(familyId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .absoluteExpiresAt(absoluteExpiresAt)
                .build();
        repository.save(token);
        return jwtService.generateRefreshToken(userId, token.getId(), expiresAt);
    }

    /**
     * Echange un jeton valide contre un nouveau (rotation). Retourne l identifiant de
     * l utilisateur et le nouveau jeton ; leve 401 dans tous les autres cas.
     */
    @Transactional
    public Rotation rotate(String refreshToken) {
        JwtService.RefreshClaims claims = parse(refreshToken);
        RefreshToken current = repository.findById(claims.jti())
                .orElseThrow(() -> new UnauthorizedException("Jeton de rafraichissement inconnu, reconnectez-vous"));
        Instant now = Instant.now();
        if (current.isRevoked()) {
            // Reutilisation d un jeton deja tourne : soit un vol, soit un client qui a perdu
            // la reponse precedente. Dans les deux cas on coupe toute la chaine.
            int n = repository.revokeFamily(current.getFamilyId(), now);
            log.warn("Reutilisation d un refresh token revoque (utilisateur {}, {} jeton(s) de la famille revoques)",
                    current.getUserId(), n);
            throw new UnauthorizedException("Session revoquee, reconnectez-vous");
        }
        if (current.getExpiresAt().isBefore(now) || current.getAbsoluteExpiresAt().isBefore(now)) {
            throw new UnauthorizedException("Session expiree, reconnectez-vous");
        }
        String next = issue(current.getUserId(), current.getFamilyId(), current.getAbsoluteExpiresAt(), now);
        current.setRevokedAt(now);
        current.setReplacedBy(jwtService.extractRefreshClaims(next).jti());
        repository.save(current);
        return new Rotation(current.getUserId(), next);
    }

    /** Deconnexion : revoque la famille du jeton presente (tous ses descendants). Silencieux si le jeton est invalide. */
    @Transactional
    public void revoke(String refreshToken) {
        Optional<JwtService.RefreshClaims> claims = tryParse(refreshToken);
        if (claims.isEmpty()) return;
        repository.findById(claims.get().jti())
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /** Suspension, correction de contact : plus aucune session ne peut se prolonger. */
    @Transactional
    public int revokeAll(UUID userId) {
        return repository.revokeAllForUser(userId, Instant.now());
    }

    @Transactional
    public int purgeExpired(Instant before) {
        return repository.deleteExpiredBefore(before);
    }

    private JwtService.RefreshClaims parse(String token) {
        try {
            return jwtService.extractRefreshClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Jeton de rafraichissement invalide ou expire");
        }
    }

    private Optional<JwtService.RefreshClaims> tryParse(String token) {
        try {
            return Optional.of(jwtService.extractRefreshClaims(token));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record Rotation(UUID userId, String refreshToken) {
    }
}
