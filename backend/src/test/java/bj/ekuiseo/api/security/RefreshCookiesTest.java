package bj.ekuiseo.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Contrat du cookie HttpOnly du refresh token et de l en-tete anti-CSRF (constats F355/F405). */
class RefreshCookiesTest {

    @Test
    void issue_buildsHttpOnlySecureStrictCookie_scopedToAuthRoutes_withRefreshTtl() {
        ResponseCookie cookie = new RefreshCookies(true, 30).issue("refresh.jwt");

        assertThat(cookie.getName()).isEqualTo("ekuiseo_refresh");
        assertThat(cookie.getValue()).isEqualTo("refresh.jwt");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
        assertThat(cookie.toString()).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth").contains("Max-Age=2592000");
    }

    /** Recette sans TLS : AUTH_COOKIE_SECURE=false retire seulement l attribut Secure. */
    @Test
    void issue_withoutSecure_keepsEveryOtherAttribute() {
        ResponseCookie cookie = new RefreshCookies(false, 7).issue("refresh.jwt");

        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    /** La suppression reprend nom, chemin et attributs de l original, avec Max-Age=0. */
    @Test
    void clear_expiresTheCookieWithTheSameScope() {
        ResponseCookie cookie = new RefreshCookies(true, 30).clear();

        assertThat(cookie.getName()).isEqualTo("ekuiseo_refresh");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    @Test
    void hasClientHeader_acceptsEitherHeader_caseInsensitively_andRefusesTheirAbsence() {
        RefreshCookies cookies = new RefreshCookies(true, 30);

        MockHttpServletRequest xhr = new MockHttpServletRequest();
        xhr.addHeader("X-Requested-With", "xmlhttprequest");
        assertThat(cookies.hasClientHeader(xhr)).isTrue();

        MockHttpServletRequest web = new MockHttpServletRequest();
        web.addHeader("X-Ekuiseo-Client", "web");
        assertThat(cookies.hasClientHeader(web)).isTrue();

        MockHttpServletRequest other = new MockHttpServletRequest();
        other.addHeader("X-Requested-With", "Fetch");
        assertThat(cookies.hasClientHeader(other)).isFalse();
        assertThat(cookies.hasClientHeader(new MockHttpServletRequest())).isFalse();
    }
}
