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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
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
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*", "/api/v1/users/*/reviews").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/geo/search").permitAll()
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
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        config.setAllowedOriginPatterns(origins.isEmpty() ? List.of("*") : origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
