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
import bj.ekuiseo.api.service.AuthService;
import bj.ekuiseo.api.service.mail.MailDeliveryException;
import bj.ekuiseo.api.web.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/auth/**} (constat F434) : routes publiques, validation des corps en 400
 * RFC 7807, erreurs metier traduites (404, 401, 409, 429, 503), service appele avec les
 * bons arguments, et quota de demandes de code du {@code RateLimitingFilter}.
 */
@WebMvcTest(controllers = AuthController.class)
class AuthControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String PHONE = "+2290197000321";

    @MockitoBean
    private AuthService authService;

    @Test
    void register_validBody_is202_andServiceReceivesTheRequest() throws Exception {
        OtpRegisterRequest request = new OtpRegisterRequest(PHONE, "Jean", "Dossou", "jean@example.test");
        when(authService.registerWithOtp(request)).thenReturn(new OtpRequestResponse("EMAIL", "je***@example.test"));

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
                        new OtpRegisterRequest(PHONE, "Jean", "Dossou", "pas-un-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("email")));
        verify(authService, never()).registerWithOtp(any());
    }

    @Test
    void register_alreadyRegistered_is409() throws Exception {
        OtpRegisterRequest request = new OtpRegisterRequest(PHONE, "Jean", "Dossou", "jean@example.test");
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
        when(authService.requestOtp(request)).thenReturn(new OtpRequestResponse("EMAIL", "je***@example.test"));

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
        when(authService.requestOtp(any())).thenReturn(new OtpRequestResponse("EMAIL", "je***@example.test"));
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

    @Test
    void verifyOtp_opensSession() throws Exception {
        OtpVerifyRequest request = new OtpVerifyRequest(PHONE, "123456");
        User user = activeUser();
        when(authService.verifyOtp(request)).thenReturn(new AuthResponse("access.jwt", "refresh.jwt",
                new UserResponse(user.getId(), PHONE, "jean@example.test", "Jean", "Dossou", null, null,
                        BigDecimal.ZERO, 0, true, true, false, user.getRole())));

        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/otp/verify"), request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.jwt"))
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()));
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
                .andExpect(status().isNoContent());
        verify(authService).logout(null);
    }

    @Test
    void logout_withRefreshToken_is204_andPassesIt() throws Exception {
        mockMvc.perform(fromNewIp(json(post("/api/v1/auth/logout"), new RefreshRequest("refresh.jwt"))))
                .andExpect(status().isNoContent());
        verify(authService).logout("refresh.jwt");
    }
}
