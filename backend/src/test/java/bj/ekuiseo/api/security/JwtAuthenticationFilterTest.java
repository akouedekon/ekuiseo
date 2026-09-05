package bj.ekuiseo.api.security;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constat F001 de l audit : les deux jetons partagent la cle de signature ; seule
 * la claim {@code type} distingue un refresh (30 jours, non revocable) d un access.
 * Le filtre doit refuser un refresh presente en Authorization: Bearer.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, 60, 30);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessToken_authenticatesActiveUser() throws Exception {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user(id, UserStatus.ACTIVE)));
        MockHttpServletRequest req = request(jwtService.generateAccessToken(id));

        filter.doFilterInternal(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void refreshToken_isRejectedAsBearer() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest req = request(jwtService.generateRefreshToken(id));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findById(any());
        verify(chain).doFilter(any(), any()); // la requete continue, anonyme
    }

    @Test
    void suspendedUser_isNotAuthenticated() throws Exception {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user(id, UserStatus.SUSPENDED)));

        filter.doFilterInternal(request(jwtService.generateAccessToken(id)), new MockHttpServletResponse(),
                mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void garbageToken_isIgnored() throws Exception {
        filter.doFilterInternal(request("not-a-jwt"), new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/me");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private static User user(UUID id, UserStatus status) {
        return User.builder().id(id).phone("+2290197000322").passwordHash("x").status(status).role(Role.USER).build();
    }
}
