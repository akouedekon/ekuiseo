package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.security.RefreshCookies;
import bj.ekuiseo.api.service.AuthService;
import bj.ekuiseo.api.service.mail.MailDeliveryException;
import bj.ekuiseo.api.web.controller.AuthController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/auth/**} (constat F434) : routes publiques, validation des corps en 400
 * RFC 7807, erreurs metier traduites (404, 401, 409, 429, 503), service appele avec les
 * bons arguments, et quota de demandes de code du {@code RateLimitingFilter}.
 *
 * <p>Constats F355/F405 : le refresh token est pose dans le cookie HttpOnly
 * {@code ekuiseo_refresh} (Secure, SameSite=Strict, Path=/api/v1/auth, Max-Age = 30 jours)
 * et absent du JSON ; {@code /refresh} et {@code /logout} le lisent depuis le cookie avec
 * l en-tete anti-CSRF (403 sans lui), ou depuis le corps pendant la transition ;
 * {@code /logout} supprime le cookie.</p>
 */
@WebMvcTest(controllers = AuthController.class)
@Import(RefreshCookies.class)
class AuthControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String PHONE = "+2290197000321";
    private static final String COOKIE = RefreshCookies.COOKIE_NAME;
    private static final int THIRTY_DAYS_SECONDS = 30 * 24 * 3600;

    private static AuthResponse session(User user, String access, String refresh) {
        return new AuthResponse(access, refresh,
                new UserResponse(user.getId(), PHONE, "jean@example.test", "Jean", "Dossou", null, null,
                        BigDecimal.ZERO, 0, true, true, false, user.getRole(), false));
    }

    @MockitoBean
    private AuthService authService;

    @Test
    void register_validBody_is202_andServiceReceivesTheRequest() throws Exception {
        OtpRegisterRequest request = new OtpRegisterRequest(PHONE, "Jean", "Dossou", "jean@example.test", true, "2026-09");
        when(authService.registerWithOtp(request)).thenReturn(new OtpRequestResponse(bj.ekuiseo.api.dto.auth.OtpChannel.EMAIL, "je***@example.test"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/register"), request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.destination").value("je***@example.test"));
        verify(authService).registerWithOtp(request);
    }

    @Test
    void register_withoutEmail_is400ValidationProblem_andServiceNotCalled() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/register"),
                        Map.of("phone", PHONE, "firstName", "Jean", "lastName", "Dossou"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("email")))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/otp/register"));
        verify(authService, never()).registerWithOtp(any());
    }

    @Test
    void register_withMalformedEmail_is400() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/register"),
                        new OtpRegisterRequest(PHONE, "Jean", "Dossou", "pas-un-email", true, "2026-09"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("email")));
        verify(authService, never()).registerWithOtp(any());
    }

    @Test
    void register_alreadyRegistered_is409() throws Exception {
        OtpRegisterRequest request = new OtpRegisterRequest(PHONE, "Jean", "Dossou", "jean@example.test", true, "2026-09");
        when(authService.registerWithOtp(request)).thenThrow(new ConflictException("Ce numero est deja inscrit"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/register"), request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"))
                .andExpect(jsonPath("$.detail").value("Ce numero est deja inscrit"));
    }

    @Test
    void requestOtp_isPublic_andReturns202() throws Exception {
        OtpRequestRequest request = new OtpRequestRequest(PHONE);
        when(authService.requestOtp(request)).thenReturn(new OtpRequestResponse(bj.ekuiseo.api.dto.auth.OtpChannel.EMAIL, "je***@example.test"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.channel").value("EMAIL"));
        verify(authService).requestOtp(request);
    }

    @Test
    void requestOtp_blankPhone_is400() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), Map.of("phone", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("phone")));
        verify(authService, never()).requestOtp(any());
    }

    @Test
    void requestOtp_unknownPhone_is404() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new NotFoundException("Numero inconnu"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void requestOtp_suspendedAccount_is401() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new UnauthorizedException("Compte suspendu"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/unauthorized"))
                .andExpect(jsonPath("$.detail").value("Compte suspendu"));
    }

    @Test
    void requestOtp_tooManyForThisNumber_is429() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new TooManyRequestsException("Trop de demandes pour ce numero"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/too-many-requests"));
    }

    @Test
    void requestOtp_mailRelayDown_is503() throws Exception {
        when(authService.requestOtp(any())).thenThrow(new MailDeliveryException("SMTP injoignable", null));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/mail-unavailable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SMTP"))));
    }

    /** Quota par IP du RateLimitingFilter sur les demandes de code (3 en test) : la 4e est refusee avant le controleur. */
    @Test
    void requestOtp_beyondIpQuota_is429FromRateLimitingFilter() throws Exception {
        when(authService.requestOtp(any())).thenReturn(new OtpRequestResponse(bj.ekuiseo.api.dto.auth.OtpChannel.EMAIL, "je***@example.test"));
        String ip = "198.51.100.7";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE)).header("X-Real-IP", ip))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(json(post("/api/v1/auth/otp/request"), new OtpRequestRequest(PHONE)).header("X-Real-IP", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/rate-limited"));
        verify(authService, org.mockito.Mockito.times(3)).requestOtp(any());
    }

    /** Le jeton d acces est dans le JSON ; le refresh token uniquement dans le cookie HttpOnly. */
    @Test
    void verifyOtp_opensSession_withRefreshTokenInHttpOnlyCookieOnly() throws Exception {
        OtpVerifyRequest request = new OtpVerifyRequest(PHONE, "123456");
        User user = activeUser();
        when(authService.verifyOtp(request)).thenReturn(session(user, "access.jwt", "refresh.jwt"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/verify"), request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(cookie().value(COOKIE, "refresh.jwt"))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().secure(COOKIE, true))
                .andExpect(cookie().sameSite(COOKIE, "Strict"))
                .andExpect(cookie().path(COOKIE, "/api/v1/auth"))
                .andExpect(cookie().maxAge(COOKIE, THIRTY_DAYS_SECONDS));
    }

    @Test
    void verifyOtp_wrongCode_is400() throws Exception {
        when(authService.verifyOtp(any())).thenThrow(new BadRequestException("Code invalide ou expire"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/verify"), new OtpVerifyRequest(PHONE, "000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/bad-request"))
                .andExpect(jsonPath("$.detail").value("Code invalide ou expire"));
    }

    @Test
    void verifyOtp_missingCode_is400ValidationProblem() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/verify"), Map.of("phone", PHONE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("code")));
        verify(authService, never()).verifyOtp(any());
    }

    /** Chemin nominal du nouveau client : cookie + en-tete X-Requested-With, sans corps. */
    @Test
    void refresh_fromCookieWithClientHeader_rotates_andSetsNewCookie() throws Exception {
        User user = activeUser();
        when(authService.refresh(new RefreshRequest("cookie.jwt"))).thenReturn(session(user, "access.2", "refresh.2"));

        mockMvc.perform(fromNewIp(post("/api/v1/auth/refresh"))
                        .cookie(new Cookie(COOKIE, "cookie.jwt"))
                        .header(RefreshCookies.REQUESTED_WITH_HEADER, RefreshCookies.REQUESTED_WITH_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.2"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().value(COOKIE, "refresh.2"))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().sameSite(COOKIE, "Strict"))
                .andExpect(cookie().path(COOKIE, "/api/v1/auth"));
        verify(authService).refresh(new RefreshRequest("cookie.jwt"));
    }

    /** L en-tete X-Ekuiseo-Client: web est accepte comme equivalent. */
    @Test
    void refresh_fromCookieWithEkuiseoClientHeader_isAccepted() throws Exception {
        when(authService.refresh(any())).thenReturn(session(activeUser(), "access.2", "refresh.2"));

        mockMvc.perform(fromNewIp(post("/api/v1/auth/refresh"))
                        .cookie(new Cookie(COOKIE, "cookie.jwt"))
                        .header(RefreshCookies.CLIENT_HEADER, RefreshCookies.CLIENT_HEADER_VALUE))
                .andExpect(status().isOk());
    }

    /** Anti-CSRF : un cookie sans l en-tete (navigation ou formulaire cross-site) est refuse avant le service. */
    @Test
    void refresh_fromCookieWithoutClientHeader_is403_andServiceNotCalled() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/auth/refresh")).cookie(new Cookie(COOKIE, "cookie.jwt")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("X-Requested-With")));
        verify(authService, never()).refresh(any());
    }

    /** Le cookie prime sur le corps quand les deux sont presents. */
    @Test
    void refresh_cookieTakesPrecedenceOverBody() throws Exception {
        when(authService.refresh(new RefreshRequest("cookie.jwt"))).thenReturn(session(activeUser(), "access.2", "refresh.2"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/refresh"), new RefreshRequest("corps.jwt")))
                        .cookie(new Cookie(COOKIE, "cookie.jwt"))
                        .header(RefreshCookies.REQUESTED_WITH_HEADER, RefreshCookies.REQUESTED_WITH_VALUE))
                .andExpect(status().isOk());
        verify(authService).refresh(new RefreshRequest("cookie.jwt"));
        verify(authService, never()).refresh(new RefreshRequest("corps.jwt"));
    }

    /** Transition : un jeton encore dans localStorage est accepte dans le corps, et la reponse pose le cookie. */
    @Test
    void refresh_legacyBody_isAccepted_andMigratesToCookie() throws Exception {
        when(authService.refresh(new RefreshRequest("ancien.jwt"))).thenReturn(session(activeUser(), "access.2", "refresh.2"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/refresh"), new RefreshRequest("ancien.jwt"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.2"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().value(COOKIE, "refresh.2"))
                .andExpect(cookie().httpOnly(COOKIE, true));
    }

    @Test
    void refresh_withoutCookieNorBody_is400() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/auth/refresh"))
                        .header(RefreshCookies.REQUESTED_WITH_HEADER, RefreshCookies.REQUESTED_WITH_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("refreshToken")));
        verify(authService, never()).refresh(any());
    }

    @Test
    void refresh_reusedToken_is401() throws Exception {
        when(authService.refresh(any())).thenThrow(new UnauthorizedException("Jeton de rafraichissement revoque"));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/refresh"), new RefreshRequest("ancien.jwt"))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Jeton de rafraichissement revoque"));
    }

    @Test
    void refresh_blankToken_is400() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/refresh"), Map.of("refreshToken", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        verify(authService, never()).refresh(any());
    }

    @Test
    void logout_withoutBody_is204_andRevokesNothingSpecific() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/auth/logout")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));
        verify(authService).logout(null);
    }

    @Test
    void logout_withRefreshToken_is204_andPassesIt() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/logout"), new RefreshRequest("refresh.jwt"))))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE, 0));
        verify(authService).logout("refresh.jwt");
    }

    /** Deconnexion du nouveau client : le cookie est lu, revoque, puis supprime (Max-Age=0, memes attributs). */
    @Test
    void logout_fromCookieWithClientHeader_revokesIt_andClearsTheCookie() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/auth/logout"))
                        .cookie(new Cookie(COOKIE, "cookie.jwt"))
                        .header(RefreshCookies.REQUESTED_WITH_HEADER, RefreshCookies.REQUESTED_WITH_VALUE))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(COOKIE, ""))
                .andExpect(cookie().maxAge(COOKIE, 0))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(cookie().secure(COOKIE, true))
                .andExpect(cookie().sameSite(COOKIE, "Strict"))
                .andExpect(cookie().path(COOKIE, "/api/v1/auth"));
        verify(authService).logout("cookie.jwt");
    }

    /** Une deconnexion forcee depuis un autre site (cookie sans en-tete) est refusee, le cookie reste. */
    @Test
    void logout_fromCookieWithoutClientHeader_is403() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/auth/logout")).cookie(new Cookie(COOKIE, "cookie.jwt")))
                .andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist(COOKIE));
        verify(authService, never()).logout(any());
    }
}
