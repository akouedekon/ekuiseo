package bj.ekuiseo.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chaine de securite dediee a la cle publique VAPID (Web Push, V20) : GET
 * {@code /api/v1/push/vapid-public-key} est public (la cle est par nature connue de chaque
 * navigateur abonne). Declaree a part de {@link SecurityConfig}, avec un {@code securityMatcher}
 * strict et une priorite superieure : elle ne s applique a aucune autre route, qui restent
 * regies par la chaine principale (JWT, roles, limitation de debit).
 */
@Configuration
public class PushSecurityConfig {

    @Bean
    @Order(10)
    public SecurityFilterChain vapidPublicKeyFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(HttpMethod.GET.name(), "/api/v1/push/vapid-public-key", "/api/v1/push/config")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
