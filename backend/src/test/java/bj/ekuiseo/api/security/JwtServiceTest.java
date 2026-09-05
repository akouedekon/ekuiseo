package bj.ekuiseo.api.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constat F027 : aucune cle publique ou d exemple ne doit permettre de demarrer. */
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-0123456789abcdef";

    @Test
    void rejectsExampleSecret() {
        assertThatThrownBy(() -> new JwtService("change-me-super-secret-key-please-override-in-prod-min-32-bytes", 60, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsBlankAndShortSecrets() {
        assertThatThrownBy(() -> new JwtService("", 60, 30)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("trop-court", 60, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 octets");
    }

    @Test
    void distinguishesAccessFromRefresh() {
        JwtService jwt = new JwtService(SECRET, 60, 30);
        UUID id = UUID.randomUUID();
        String access = jwt.generateAccessToken(id);
        String refresh = jwt.generateRefreshToken(id);

        assertThat(jwt.extractUserIdFromAccessToken(access)).isEqualTo(id);
        assertThat(jwt.isRefreshToken(refresh)).isTrue();
        assertThat(jwt.isRefreshToken(access)).isFalse();
        assertThatThrownBy(() -> jwt.extractUserIdFromAccessToken(refresh)).isInstanceOf(JwtException.class);
    }
}
