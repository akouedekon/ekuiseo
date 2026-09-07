package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaine complete (filtre JWT + regles de SecurityConfig + controleur + base reelle) sur
 * {@code /api/v1/admin/**} : reserve a ROLE_ADMIN, reponses d'erreur en RFC 7807.
 * Les regles unitaires (jeton expire, refresh presente comme acces...) sont couvertes
 * en {@code @WebMvcTest} par {@code SecurityRulesWebMvcTest} ; ici, le compte est lu en
 * base par {@code JwtAuthenticationFilter}.
 */
@AutoConfigureMockMvc
class AdminAuthorizationIT extends AbstractPostgisIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String regularUserToken;
    private String adminToken;
    private String suspendedAdminToken;

    @BeforeEach
    void setUp() {
        User regularUser = newUser("Jean", Role.USER);
        User admin = newUser("Awa", Role.ADMIN);
        User suspendedAdmin = newUser("Suspendu", Role.ADMIN, UserStatus.SUSPENDED);

        regularUserToken = jwtService.generateAccessToken(regularUser.getId());
        adminToken = jwtService.generateAccessToken(admin.getId());
        suspendedAdminToken = jwtService.generateAccessToken(suspendedAdmin.getId());
    }

    @Test
    void nonAdminUser_isForbiddenFromAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regularUserToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/audit-log"));
    }

    @Test
    void adminUser_canAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void adminUser_canReadLiquidityAndStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/liquidity").param("days", "30")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.northStar.monthlyTarget").value(2000));
        mockMvc.perform(get("/api/v1/admin/stats").param("days", "7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(7));
    }

    @Test
    void anonymousRequest_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    /** Un compte suspendu perd l'acces immediatement, meme avec un jeton encore valide et le role ADMIN. */
    @Test
    void suspendedAdmin_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + suspendedAdminToken))
                .andExpect(status().isUnauthorized());
    }
}
