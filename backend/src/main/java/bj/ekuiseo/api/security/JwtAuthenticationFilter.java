package bj.ekuiseo.api.security;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Filtre extrayant le JWT du header Authorization et peuplant le contexte de securite. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                UUID userId = jwtService.extractUserId(token);
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    // Un compte suspendu perd l'acces immediatement, sans attendre l'expiration du jeton.
                    Optional<User> user = userRepository.findById(userId)
                            .filter(u -> u.getStatus() == UserStatus.ACTIVE);
                    user.ifPresent(u -> {
                        EkuiseoUserDetails principal = EkuiseoUserDetails.from(u);
                        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Jeton invalide ou expire : on laisse la requete anonyme, elle sera
                // rejetee plus loin par les regles d'autorisation si l'endpoint le requiert.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
