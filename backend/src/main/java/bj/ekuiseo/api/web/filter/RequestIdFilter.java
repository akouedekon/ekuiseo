package bj.ekuiseo.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Correlation des journaux par requete (constat F439). En tete de chaine (avant la chaine
 * Spring Security, donc avant JwtAuthenticationFilter, grace a {@link Ordered#HIGHEST_PRECEDENCE}
 * sur un filtre servlet declare comme bean) :
 * <ul>
 *   <li>lit {@code X-Request-Id} s il est fourni par le proxy (Caddy : {http.request.uuid}) et
 *       de forme sure, sinon en genere un ;</li>
 *   <li>le pose dans le MDC ({@code requestId}) - {@code userId} y est ajoute plus loin par
 *       JwtAuthenticationFilter une fois l appelant authentifie - et le renvoie en en-tete
 *       de reponse pour que le front et le support puissent le citer ;</li>
 *   <li>emet un journal INFO par requete terminee (methode, chemin, statut, duree en ms),
 *       sauf pour les sondes {@code /actuator/health} qui noieraient le journal ;</li>
 *   <li>vide le MDC en {@code finally} : les threads Tomcat sont reutilises.</li>
 * </ul>
 * Le chemin journalise est l URI sans la chaine de requete : les parametres peuvent porter
 * des donnees personnelles (numero, libelle d adresse).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";

    private static final Logger log = LoggerFactory.getLogger("bj.ekuiseo.api.access");
    /** Identifiant accepte tel quel : chiffres, lettres, tiret, point, soulignement ; 8 a 64 caracteres. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final String HEALTH_PREFIX = "/actuator/health";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        long startedAt = System.nanoTime();
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (!path.startsWith(HEALTH_PREFIX)) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info("{} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(), durationMs);
            }
            MDC.clear();
        }
    }

    /** Conserve un identifiant amont sur, sinon en genere un (16 caracteres hexadecimaux). */
    static String resolveRequestId(String incoming) {
        if (incoming != null) {
            String candidate = incoming.trim();
            if (SAFE_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }
}
