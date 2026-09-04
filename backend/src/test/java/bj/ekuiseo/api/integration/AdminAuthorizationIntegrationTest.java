package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifie que /api/v1/admin/** est reserve a ROLE_ADMIN (regle metier n.13,
 * gestion des roles et back-office) : un utilisateur authentifie mais non
 * admin recoit 403, un administrateur recoit 200.
 *
 * <p><b>DESACTIVE PAR DEFAUT</b>, pour la meme raison que
 * {@link TripSearchIntegrationTest} : pas d'acces au registre Docker dans ce
 * sandbox pour l'image postgis/postgis. Retirez {@code @Disabled} sur un
 * poste disposant d'un acces Docker complet.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Disabled("Necessite Docker + acces au registre pour l'image postgis/postgis (indisponible dans ce sandbox)")
class AdminAuthorizationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgis = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ekuiseo")
            .withUsername("ekuiseo")
            .withPassword("ekuiseo");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String regularUserToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        User regularUser = userRepository.save(User.builder()
                .phone("+22997000010")
                .firstName("Jean")
                .lastName("Passager")
                .passwordHash("x")
                .status(UserStatus.ACTIVE)
                .role(Role.USER)
                .build());
        User admin = userRepository.save(User.builder()
                .phone("+22997000011")
                .firstName("Awa")
                .lastName("Admin")
                .passwordHash("x")
                .status(UserStatus.ACTIVE)
                .role(Role.ADMIN)
                .build());

        regularUserToken = jwtService.generateAccessToken(regularUser.getId());
        adminToken = jwtService.generateAccessToken(admin.getId());
    }

    @Test
    void nonAdminUser_isForbiddenFromAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regularUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUser_canAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousRequest_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized());
    }
}
