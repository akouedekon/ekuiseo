package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.security.RefreshCookies;
import bj.ekuiseo.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inscription et connexion par code e-mail (OTP), rafraichissement et deconnexion.
 * Toutes les routes sont publiques (voir SecurityConfig) mais soumises a limitation de
 * debit (RateLimitingFilter : 20 requetes/60 s/IP sur /auth/**, plus 10 demandes de
 * code / 10 min / IP sur /otp/request et /otp/register). Aucun mot de passe : le
 * parcours OTP est le seul.
 *
 * <p>Jeton de rafraichissement (constats F355/F405) : il n apparait plus dans le JSON
 * ({@code refreshToken} nul, champ conserve pour la compatibilite du contrat) mais dans
 * le cookie {@code ekuiseo_refresh} decrit par {@link RefreshCookies}. Periode de
 * transition : {@code /refresh} et {@code /logout} acceptent encore un jeton dans le
 * corps ({@code {"refreshToken"}}) pour les navigateurs qui en gardent un de l ancien
 * client dans localStorage ; la reponse pose alors le cookie et le client efface son
 * stockage. Le cookie est prioritaire des qu il est present, et exige alors l en-tete
 * anti-CSRF (403 sinon).</p>
 */
@Tag(name = "Authentification", description = "Inscription et connexion par code e-mail (OTP), rafraichissement et deconnexion. Aucun mot de passe. Le refresh token voyage dans le cookie HttpOnly ekuiseo_refresh.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies refreshCookies;

    public AuthController(AuthService authService, RefreshCookies refreshCookies) {
        this.authService = authService;
        this.refreshCookies = refreshCookies;
    }

    @Operation(summary = "Inscription par OTP", description = "Cree le compte en attente de verification (prenom, nom, e-mail obligatoire, numero E.164 ou beninois a 10 chiffres) et envoie le code de connexion a l adresse e-mail. Renvoie le canal et la destination masquee. La session s ouvre ensuite via /otp/verify, qui active le compte. 409 si le numero (deja verifie) ou l e-mail est deja inscrit ; un numero jamais verifie est repris.")
    @PostMapping("/otp/register")
    public ResponseEntity<OtpRequestResponse> registerWithOtp(@Valid @RequestBody OtpRegisterRequest req) {
        return ResponseEntity.accepted().body(authService.registerWithOtp(req));
    }

    @Operation(summary = "Demander un code", description = "Envoie un code a 6 chiffres a l adresse e-mail du compte (SMS en repli si configure), valable 5 minutes. Renvoie le canal et la destination masquee. 404 si le numero est inconnu, 401 si le compte est suspendu, 429 au-dela de 3 demandes/10 min par numero.")
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponse> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        return ResponseEntity.accepted().body(authService.requestOtp(req));
    }

    @Operation(summary = "Verifier un code", description = "Valide le code et ouvre la session (active un compte en attente, marque l e-mail ou le numero comme verifie selon le canal). Le code est invalide au-dela de 5 tentatives incorrectes. Le jeton d acces est dans le JSON, le refresh token dans le cookie HttpOnly ekuiseo_refresh (Path=/api/v1/auth).")
    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return withSessionCookie(authService.verifyOtp(req));
    }

    @Operation(summary = "Rafraichir les jetons", description = "Echange le refresh token (cookie ekuiseo_refresh, avec l en-tete X-Requested-With: XMLHttpRequest ; ou, en transition, le corps {refreshToken}) contre une nouvelle paire : rotation, l ancien est revoque, le nouveau cookie est pose. 403 si le cookie est presente sans l en-tete anti-CSRF, 401 si le jeton est revoque ou expire (une reutilisation revoque toute la chaine, une session ne depasse jamais 90 jours).")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshCookies.COOKIE_NAME, required = false) String cookieToken,
            @Valid @RequestBody(required = false) RefreshRequest body,
            HttpServletRequest request) {
        String token = resolveToken(cookieToken, body, request);
        if (token == null) {
            throw new BadRequestException("refreshToken: jeton de rafraichissement absent (cookie ou corps)");
        }
        return withSessionCookie(authService.refresh(new RefreshRequest(token)));
    }

    @Operation(summary = "Se deconnecter", description = "Revoque le refresh token presente (cookie ekuiseo_refresh avec l en-tete X-Requested-With, ou corps {refreshToken}) et toute sa chaine de rotation, puis supprime le cookie. 204 meme si le jeton est absent ou deja invalide ; 403 si le cookie est presente sans l en-tete anti-CSRF.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest body,
            HttpServletRequest request) {
        authService.logout(resolveToken(cookieToken, body, request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    /**
     * Jeton a utiliser : le cookie s il est present (l en-tete anti-CSRF est alors
     * obligatoire, 403 sinon), sinon le corps (transition), sinon null.
     */
    private String resolveToken(String cookieToken, RefreshRequest body, HttpServletRequest request) {
        if (cookieToken != null && !cookieToken.isBlank()) {
            if (!refreshCookies.hasClientHeader(request)) {
                throw new ForbiddenException("En-tete " + RefreshCookies.REQUESTED_WITH_HEADER
                        + " requis pour utiliser le cookie de session");
            }
            return cookieToken;
        }
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body.refreshToken();
        }
        return null;
    }

    /** Pose le cookie du refresh token et retire celui-ci du JSON. */
    private ResponseEntity<AuthResponse> withSessionCookie(AuthResponse session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(session.refreshToken()).toString())
                .body(session.withoutRefreshToken());
    }
}
