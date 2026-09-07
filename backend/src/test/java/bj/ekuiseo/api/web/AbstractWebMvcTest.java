package bj.ekuiseo.api.web;

import bj.ekuiseo.api.config.SecurityConfig;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.when;

/**
 * Socle des tests de controleurs ({@code @WebMvcTest}, constat F434) : la tranche MVC
 * complete avec la vraie chaine de securite, sans base de donnees.
 *
 * <ul>
 *   <li>{@link SecurityConfig} est importee explicitement (une {@code @Configuration}
 *       n'est pas prise par la tranche) : regles d'autorisation, reponses 401/403 en
 *       RFC 7807, {@code JwtAuthenticationFilter} (instancie par SecurityConfig, pas un bean).</li>
 *   <li>{@link JwtService} est le vrai service, avec un secret de test : les jetons des
 *       tests sont de vrais JWT signes, expires ou de type refresh selon le cas.</li>
 *   <li>{@code RateLimitingFilter} (un {@code Filter}) et {@code GlobalExceptionHandler}
 *       (un {@code @RestControllerAdvice}) sont inclus d'office par la tranche.</li>
 *   <li>{@link UserRepository} est mocke : c'est la seule dependance de la securite ;
 *       {@link #bearerFor(User)} l'alimente pour que le filtre retrouve le compte.</li>
 *   <li>{@link CurrentUser} est le vrai composant (lit le contexte de securite).</li>
 * </ul>
 * Chaque test envoie un en-tete {@code X-Real-IP} distinct ({@link #fromNewIp}) pour que
 * le quota de {@code RateLimitingFilter}, partage par le contexte Spring, ne se cumule
 * pas d'un test a l'autre.
 */
@Import({SecurityConfig.class, JwtService.class, CurrentUser.class})
@TestPropertySource(properties = {
        "ekuiseo.jwt.secret=" + AbstractWebMvcTest.JWT_SECRET,
        "ekuiseo.jwt.access-token-ttl-minutes=60",
        "ekuiseo.jwt.refresh-token-ttl-days=30",
        "ekuiseo.cors.allowed-origins=*",
        "ekuiseo.rate-limit.auth.max-requests=100",
        "ekuiseo.rate-limit.auth.window-seconds=60",
        "ekuiseo.rate-limit.otp.max-requests=3",
        "ekuiseo.rate-limit.otp.window-seconds=600",
        "ekuiseo.rate-limit.webhook.max-requests=100"
})
public abstract class AbstractWebMvcTest {

    /** Au moins 32 octets : JwtService refuse de demarrer sinon. */
    static final String JWT_SECRET = "webmvc-test-secret-key-not-for-production-0123456789";

    private static final AtomicInteger IP_SEQ = new AtomicInteger(0);

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected ObjectMapper objectMapper;
    @MockitoBean
    protected UserRepository userRepository;

    protected User user(Role role, UserStatus status) {
        UUID id = UUID.randomUUID();
        return User.builder()
                .id(id)
                .phone("+2290197" + String.format("%06d", Math.abs(id.hashCode()) % 1_000_000))
                .firstName("Test").lastName("Utilisateur")
                .email(id + "@example.test")
                .passwordHash("x")
                .role(role)
                .status(status)
                .build();
    }

    protected User activeUser() {
        return user(Role.USER, UserStatus.ACTIVE);
    }

    protected User activeAdmin() {
        return user(Role.ADMIN, UserStatus.ACTIVE);
    }

    /** Jeton d'acces valide pour ce compte, que le filtre JWT retrouvera via le depot mocke. */
    protected String bearerFor(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return "Bearer " + jwtService.generateAccessToken(user.getId());
    }

    /** Jeton d'acces deja expire, signe avec la meme cle. */
    protected String expiredBearerFor(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        JwtService expiringImmediately = new JwtService(JWT_SECRET, -1, 30);
        return "Bearer " + expiringImmediately.generateAccessToken(user.getId());
    }

    /** Refresh token presente comme jeton d'acces : doit etre refuse. */
    protected String refreshAsBearerFor(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return "Bearer " + jwtService.generateRefreshToken(user.getId());
    }

    protected MockHttpServletRequestBuilder fromNewIp(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Real-IP", "10.42." + (IP_SEQ.incrementAndGet() / 250) + "." + (IP_SEQ.get() % 250 + 1));
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String bearer) {
        return fromNewIp(builder).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }
}
