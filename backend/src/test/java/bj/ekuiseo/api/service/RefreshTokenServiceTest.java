package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.RefreshToken;
import bj.ekuiseo.api.repository.RefreshTokenRepository;
import bj.ekuiseo.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F001 : rotation, detection de reutilisation, revocation, borne absolue. */
class RefreshTokenServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-0123456789abcdef";

    private final JwtService jwt = new JwtService(SECRET, 60, 30);
    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final Map<UUID, RefreshToken> store = new HashMap<>();
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            store.put(t.getId(), t);
            return t;
        });
        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(repository.revokeFamily(any(), any())).thenAnswer(inv -> {
            UUID family = inv.getArgument(0);
            int n = 0;
            for (RefreshToken t : store.values()) {
                if (t.getFamilyId().equals(family) && t.getRevokedAt() == null) { t.setRevokedAt(inv.getArgument(1)); n++; }
            }
            return n;
        });
        service = new RefreshTokenService(repository, jwt, 30, 90);
    }

    @Test
    void rotationRevokesTheOldTokenAndKeepsTheFamily() {
        UUID user = UUID.randomUUID();
        String first = service.issue(user);
        UUID firstJti = jwt.extractRefreshClaims(first).jti();

        RefreshTokenService.Rotation rotation = service.rotate(first);

        assertThat(rotation.userId()).isEqualTo(user);
        UUID secondJti = jwt.extractRefreshClaims(rotation.refreshToken()).jti();
        assertThat(store.get(firstJti).isRevoked()).isTrue();
        assertThat(store.get(firstJti).getReplacedBy()).isEqualTo(secondJti);
        assertThat(store.get(secondJti).getFamilyId()).isEqualTo(store.get(firstJti).getFamilyId());
        assertThat(store.get(secondJti).getAbsoluteExpiresAt()).isEqualTo(store.get(firstJti).getAbsoluteExpiresAt());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        UUID user = UUID.randomUUID();
        String first = service.issue(user);
        String second = service.rotate(first).refreshToken();

        assertThatThrownBy(() -> service.rotate(first)).isInstanceOf(UnauthorizedException.class);
        // Le jeton legitime (second) est lui aussi revoque : l utilisateur doit se reconnecter.
        assertThatThrownBy(() -> service.rotate(second)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unknownForgedOrAccessTokensAreRejected() {
        UUID user = UUID.randomUUID();
        String unknown = jwt.generateRefreshToken(user); // signe mais jamais enregistre
        assertThatThrownBy(() -> service.rotate(unknown)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate(jwt.generateAccessToken(user))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate("garbage")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void absoluteExpiryStopsRotation() {
        UUID user = UUID.randomUUID();
        String token = service.issue(user);
        RefreshToken row = store.get(jwt.extractRefreshClaims(token).jti());
        row.setAbsoluteExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expiree");
    }

    @Test
    void logoutRevokesTheFamilyAndToleratesGarbage() {
        UUID user = UUID.randomUUID();
        String token = service.issue(user);
        service.revoke(token);
        assertThat(store.values()).allMatch(RefreshToken::isRevoked);
        service.revoke("not-a-token"); // silencieux
        service.revokeAll(user);
        verify(repository).revokeAllForUser(eq(user), any());
    }
}
