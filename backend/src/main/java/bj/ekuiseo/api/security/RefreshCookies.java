package bj.ekuiseo.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cookie du jeton de rafraichissement (constats F355/F405 de l audit).
 *
 * <p>Le refresh token n est plus remis dans le corps JSON de {@code /auth/otp/verify} et
 * {@code /auth/refresh} : il voyage dans un cookie {@value #COOKIE_NAME} que le
 * JavaScript de la page ne peut pas lire ({@code HttpOnly}). Un script tiers ou une
 * extension compromise n obtient donc au pire que le jeton d acces (60 min, en memoire),
 * jamais les 30 jours de session.</p>
 *
 * <p>Contrat exact du cookie :</p>
 * <ul>
 *   <li>{@code HttpOnly} : inaccessible a {@code document.cookie}.</li>
 *   <li>{@code Secure} : transmis en HTTPS seulement. Les navigateurs acceptent un cookie
 *       Secure sur {@code http://localhost} (Chrome 89+, Firefox 75+), le developpement
 *       local n a donc rien a configurer ; {@code AUTH_COOKIE_SECURE=false} n est utile
 *       que pour un hote HTTP autre que localhost.</li>
 *   <li>{@code SameSite=Strict} : jamais envoye depuis une navigation ou une requete
 *       initiee par un autre site, premiere barriere CSRF.</li>
 *   <li>{@code Path=/api/v1/auth} : seules les routes d authentification le recoivent,
 *       le reste de l API continue de lire l en-tete {@code Authorization}.</li>
 *   <li>{@code Max-Age} = duree glissante du refresh token
 *       ({@code ekuiseo.jwt.refresh-token-ttl-days}) ; la borne absolue de 90 jours reste
 *       verifiee en base par {@code RefreshTokenService}.</li>
 * </ul>
 *
 * <p>Seconde barriere CSRF : quand le jeton vient du cookie, la requete doit porter
 * l en-tete {@code X-Requested-With: XMLHttpRequest} (ou {@code X-Ekuiseo-Client: web}).
 * Un navigateur n ajoute jamais cet en-tete a une navigation ou a un formulaire
 * cross-site, et une requete {@code fetch} d une autre origine qui le porterait
 * declencherait un preflight CORS refuse (credentials non autorises, voir
 * {@code SecurityConfig}).</p>
 */
@Component
public class RefreshCookies {

    public static final String COOKIE_NAME = "ekuiseo_refresh";
    public static final String COOKIE_PATH = "/api/v1/auth";
    public static final String REQUESTED_WITH_HEADER = "X-Requested-With";
    public static final String REQUESTED_WITH_VALUE = "XMLHttpRequest";
    public static final String CLIENT_HEADER = "X-Ekuiseo-Client";
    public static final String CLIENT_HEADER_VALUE = "web";

    private final boolean secure;
    private final Duration maxAge;

    public RefreshCookies(@Value("${ekuiseo.auth.cookie-secure:true}") boolean secure,
                          @Value("${ekuiseo.jwt.refresh-token-ttl-days:30}") long refreshTtlDays) {
        this.secure = secure;
        this.maxAge = Duration.ofDays(refreshTtlDays);
    }

    /** Cookie portant un jeton frais (connexion ou rotation). */
    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    /** Cookie de suppression ({@code Max-Age=0}, memes attributs pour que le navigateur retrouve l original). */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /** Vrai si la requete porte l un des deux en-tetes qu un navigateur n envoie jamais en cross-site. */
    public boolean hasClientHeader(HttpServletRequest request) {
        return REQUESTED_WITH_VALUE.equalsIgnoreCase(request.getHeader(REQUESTED_WITH_HEADER))
                || CLIENT_HEADER_VALUE.equalsIgnoreCase(request.getHeader(CLIENT_HEADER));
    }

    public boolean isSecure() {
        return secure;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }
}
