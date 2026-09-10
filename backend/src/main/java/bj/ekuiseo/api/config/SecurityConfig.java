package bj.ekuiseo.api.config;

import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtAuthenticationFilter;
import bj.ekuiseo.api.security.JwtService;
import bj.ekuiseo.api.security.RateLimitingFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${ekuiseo.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository,
                                            RateLimitingFilter rateLimitingFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/payments/kkiapay/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/trips/search", "/api/v1/trips/*", "/api/v1/trips/*/stops").permitAll()
                        // Apercu de partage (Open Graph) servi aux robots WhatsApp/Facebook (ShareController, constat F341).
                        .requestMatchers(HttpMethod.GET, "/share/trips/*").permitAll()
                        // Suivi en direct par jeton (TripLiveController, V23) : un proche du passager suit le
                        // vehicule sans compte ; le jeton est le secret, quota par IP dans RateLimitingFilter.
                        .requestMatchers(HttpMethod.GET, "/api/v1/live/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*", "/api/v1/users/*/reviews").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/geo/search", "/api/v1/geo/places").permitAll()
                        // Rapports d erreur du navigateur (ClientErrorController, constat F440) : ils doivent
                        // partir meme sans session ; quota par IP dans RateLimitingFilter.
                        .requestMatchers(HttpMethod.POST, "/api/v1/client-errors").permitAll()
                        // Actuator : health et info restent publics (sondes de disponibilite standard),
                        // le reste (metrics, env, etc.) est reserve au back-office (donnees d'exploitation
                        // sensibles : versions, configuration, metriques internes).
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Le dispatch d'erreur de Tomcat (/error) n'est pas traverse par le filtre JWT
                        // (OncePerRequestFilter) : sans cette regle, un 403 legitime ressortait en 401
                        // anonyme sur la page d'erreur. Les reponses 401/403 sont de toute facon
                        // ecrites directement ci-dessous, sans passer par /error.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED, "Authentification requise",
                                        "Connectez-vous pour acceder a cette ressource", request.getRequestURI()))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeProblem(response, HttpStatus.FORBIDDEN, "Acces refuse",
                                        "Vous n'avez pas les droits necessaires pour cette action", request.getRequestURI())))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /** Reponse d'erreur de securite au format RFC 7807, coherente avec GlobalExceptionHandler. */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail,
                                     String instance) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String body = "{\"type\":\"https://ekuiseo.bj/problems/" + status.name().toLowerCase().replace('_', '-')
                + "\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + instance + "\"}";
        response.getWriter().write(body);
    }

    /**
     * Origines autorisees : liste separee par des virgules (CORS_ALLOWED_ORIGINS),
     * "*" par defaut pour le developpement. En production, restreindre au domaine
     * public (et a la vitrine GitHub Pages si elle est conservee).
     *
     * <p>Cookie de session (constats F355/F405) : le refresh token voyage dans le cookie
     * HttpOnly {@code ekuiseo_refresh} (SameSite=Strict, Path=/api/v1/auth, voir
     * {@code RefreshCookies}). {@code allowCredentials} reste a false : front et API sont
     * servis par le meme domaine (ekuiseo.com via Caddy), le cookie part donc en
     * same-origin sans CORS. Consequence assumee : une origine tierce, dont la vitrine
     * GitHub Pages, ne peut plus ouvrir ni rafraichir une session (le navigateur n envoie
     * ni ne stocke le cookie sans credentials, et SameSite=Strict l interdirait de toute
     * facon) ; elle reste limitee aux routes publiques (recherche, fiches). Le
     * {@code csrf.disable()} ci-dessus est conserve (API sans session serveur) : la
     * protection CSRF du cookie tient a SameSite=Strict et a l en-tete
     * {@code X-Requested-With} exige par {@code AuthController} sur /refresh et /logout.</p>
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        config.setAllowedOriginPatterns(origins.isEmpty() ? List.of("*") : origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Le jeton d acces voyage en en-tete Authorization et le cookie de rafraichissement
        // n est utile qu en same-origin : sans credentials, une origine "*" reste sans
        // danger, et une origine reflechie avec credentials (constat L2 de l audit)
        // devient impossible par construction.
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
