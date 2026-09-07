package bj.ekuiseo.api.web;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.service.AccountDeletionService;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.EmailChangeService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.PayoutService;
import bj.ekuiseo.api.service.TripService;
import bj.ekuiseo.api.service.UserService;
import bj.ekuiseo.api.web.controller.MeController;
import bj.ekuiseo.api.web.controller.admin.AdminPayoutController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regles de {@code SecurityConfig} et de {@code JwtAuthenticationFilter} sur la tranche MVC
 * (constat F434) : qui passe, qui est refuse, et toujours en {@code application/problem+json}.
 * Deux controleurs suffisent : {@code /api/v1/me} (authentifie) et {@code /api/v1/admin/payouts}
 * (ROLE_ADMIN).
 */
@WebMvcTest(controllers = {MeController.class, AdminPayoutController.class})
class SecurityRulesWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private TripService tripService;
    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private MessageService messageService;
    @MockitoBean
    private EmailChangeService emailChangeService;
    @MockitoBean
    private AccountDeletionService accountDeletionService;
    @MockitoBean
    private PayoutService payoutService;

    @Test
    void anonymous_onProtectedRoute_is401ProblemJson() throws Exception {
        mockMvc.perform(fromNewIp(get("/api/v1/me")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/unauthorized"))
                .andExpect(jsonPath("$.title").value("Authentification requise"))
                .andExpect(jsonPath("$.instance").value("/api/v1/me"));
        verify(userService, never()).getMe(any());
    }

    @Test
    void validAccessToken_isAuthenticated_andCurrentUserResolvesToTokenSubject() throws Exception {
        User user = activeUser();
        when(userService.getMe(user.getId())).thenReturn(userResponse(user));

        mockMvc.perform(authed(get("/api/v1/me"), bearerFor(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.role").value("USER"));
        verify(userService).getMe(user.getId());
    }

    @Test
    void regularUser_onAdminRoute_is403ProblemJson() throws Exception {
        mockMvc.perform(authed(get("/api/v1/admin/payouts"), bearerFor(activeUser())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/payouts"));
        verify(payoutService, never()).listAllForAdmin();
    }

    @Test
    void admin_onAdminRoute_is200() throws Exception {
        when(payoutService.listAllForAdmin()).thenReturn(List.of());

        mockMvc.perform(authed(get("/api/v1/admin/payouts"), bearerFor(activeAdmin())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    /** Un ADMIN reste un USER : il accede aussi aux routes authentifiees ordinaires. */
    @Test
    void admin_onUserRoute_is200() throws Exception {
        User admin = activeAdmin();
        when(userService.getMe(admin.getId())).thenReturn(userResponse(admin));

        mockMvc.perform(authed(get("/api/v1/me"), bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void expiredAccessToken_is401() throws Exception {
        User user = activeUser();

        mockMvc.perform(authed(get("/api/v1/me"), expiredBearerFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verify(userService, never()).getMe(any());
    }

    @Test
    void suspendedAccount_withValidToken_is401() throws Exception {
        User suspended = user(Role.USER, UserStatus.SUSPENDED);

        mockMvc.perform(authed(get("/api/v1/me"), bearerFor(suspended)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(userService, never()).getMe(any());
    }

    /** Un compte suspendu perd aussi ses droits d'administration, jeton valide ou non. */
    @Test
    void suspendedAdmin_onAdminRoute_is401() throws Exception {
        User suspendedAdmin = user(Role.ADMIN, UserStatus.SUSPENDED);

        mockMvc.perform(authed(get("/api/v1/admin/payouts"), bearerFor(suspendedAdmin)))
                .andExpect(status().isUnauthorized());
        verify(payoutService, never()).listAllForAdmin();
    }

    @Test
    void pendingVerificationAccount_is401() throws Exception {
        User pending = user(Role.USER, UserStatus.PENDING_VERIFICATION);

        mockMvc.perform(authed(get("/api/v1/me"), bearerFor(pending)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenPresentedAsAccessToken_is401() throws Exception {
        User user = activeUser();

        mockMvc.perform(authed(get("/api/v1/me"), refreshAsBearerFor(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(userService, never()).getMe(any());
    }

    @Test
    void tokenSignedWithAnotherKey_is401() throws Exception {
        User user = activeUser();
        String forged = new bj.ekuiseo.api.security.JwtService(
                "another-secret-key-of-at-least-32-bytes-0123456789", 60, 30).generateAccessToken(user.getId());

        mockMvc.perform(fromNewIp(get("/api/v1/me")).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void malformedAuthorizationHeader_is401() throws Exception {
        mockMvc.perform(fromNewIp(get("/api/v1/me")).header(HttpHeaders.AUTHORIZATION, "Bearer pas-un-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(get("/api/v1/me")).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserId_inValidToken_is401() throws Exception {
        String token = "Bearer " + jwtService.generateAccessToken(UUID.randomUUID());

        mockMvc.perform(fromNewIp(get("/api/v1/me")).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    private static UserResponse userResponse(User user) {
        return new UserResponse(user.getId(), user.getPhone(), user.getEmail(), user.getFirstName(), user.getLastName(),
                null, null, BigDecimal.ZERO, 0, true, true, false, user.getRole());
    }
}
