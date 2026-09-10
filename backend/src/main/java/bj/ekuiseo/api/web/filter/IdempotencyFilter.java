package bj.ekuiseo.api.web.filter;

import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * En-tete {@code Idempotency-Key} sur les ecritures financieres (contrat A.1) :
 * <ul>
 *   <li>{@code POST /api/v1/trips/{id}/bookings}</li>
 *   <li>{@code POST /api/v1/bookings/{id}/payments/deposit}</li>
 *   <li>{@code POST /api/v1/payments/{id}/confirm}</li>
 *   <li>{@code POST /api/v1/bookings/{id}/cancel}</li>
 * </ul>
 * Sans en-tete, rien ne change. Avec : la premiere reponse (2xx ou 4xx, corps JSON) est
 * memorisee et rejouee a l identique avec {@code Idempotency-Replayed: true} ; un corps
 * different sous la meme cle vaut 422 ; une requete encore en cours sous la meme cle vaut 409 ;
 * une erreur serveur n est pas memorisee (le client peut reessayer avec la meme cle).
 *
 * <p>Enregistre comme filtre de servlet ordinaire (apres la chaine Spring Security) : l identite
 * vient du contexte de securite, jamais du client. Le corps de la requete est lu en memoire pour
 * etre hache puis rejoue au controleur (corps JSON de quelques centaines d octets au plus).</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    public static final String MISMATCH_TYPE = "https://ekuiseo.com/problems/idempotency-key-reuse";
    /** Corps memorise au plus : au-dela, la reponse n est pas rejouable (jamais atteint par ces routes). */
    private static final int MAX_STORED_BODY = 64 * 1024;

    private static final List<Pattern> ROUTES = List.of(
            Pattern.compile("^/api/v1/trips/[^/]+/bookings$"),
            Pattern.compile("^/api/v1/bookings/[^/]+/payments/deposit$"),
            Pattern.compile("^/api/v1/payments/[^/]+/confirm$"),
            Pattern.compile("^/api/v1/bookings/[^/]+/cancel$"));

    private final ObjectProvider<IdempotencyService> idempotencyService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(ObjectProvider<IdempotencyService> idempotencyService, CurrentUser currentUser,
                             ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    static boolean applies(String method, String path) {
        if (!"POST".equals(method) || path == null) return false;
        for (Pattern route : ROUTES) {
            if (route.matcher(path).matches()) return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !applies(request.getMethod(), request.getRequestURI())
                || request.getHeader(HEADER) == null
                || idempotencyService.getIfAvailable() == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        IdempotencyService service = idempotencyService.getObject();
        String key = request.getHeader(HEADER).trim();
        if (!IdempotencyService.isWellFormed(key)) {
            writeProblem(response, 400, "https://ekuiseo.bj/problems/validation-error", "Requete invalide",
                    "L en-tete Idempotency-Key doit faire entre " + IdempotencyService.KEY_MIN_LENGTH + " et "
                            + IdempotencyService.KEY_MAX_LENGTH + " caracteres", request.getRequestURI());
            return;
        }
        UUID userId = currentUser.idOrNull();
        if (userId == null) {
            // La chaine de securite a deja repondu 401 pour ces routes ; par prudence on laisse passer.
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String route = request.getMethod() + " " + request.getRequestURI();
        IdempotencyService.Outcome outcome = service.begin(key, userId, route, IdempotencyService.hashBody(body));
        switch (outcome.decision()) {
            case REPLAY -> {
                replay(response, outcome);
                return;
            }
            case MISMATCH -> {
                writeProblem(response, 422, MISMATCH_TYPE, "Cle d idempotence reutilisee",
                        "Cette cle Idempotency-Key a deja servi pour une requete differente", request.getRequestURI());
                return;
            }
            case IN_FLIGHT -> {
                writeProblem(response, 409, "https://ekuiseo.bj/problems/conflict", "Requete en cours",
                        "Une requete identique est deja en cours de traitement", request.getRequestURI());
                return;
            }
            case PROCEED -> {
                // suite ci-dessous
            }
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            byte[] produced = wrapped.getContentAsByteArray();
            if (status < 500 && produced.length <= MAX_STORED_BODY && isJson(produced)) {
                service.complete(outcome.keyId(), status, produced.length == 0 ? null
                        : new String(produced, StandardCharsets.UTF_8));
                completed = true;
            }
        } finally {
            if (!completed) {
                service.abandon(outcome.keyId());
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyService.Outcome outcome) throws IOException {
        int status = outcome.responseStatus();
        response.setStatus(status);
        response.setHeader(REPLAYED_HEADER, "true");
        response.setCharacterEncoding("UTF-8");
        if (outcome.responseBody() != null) {
            response.setContentType(status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON_VALUE : MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(outcome.responseBody());
        }
        log.info("Reponse rejouee (Idempotency-Key) : {} {}", status, outcome.keyId());
    }

    private boolean isJson(byte[] produced) {
        if (produced.length == 0) return true;
        try {
            objectMapper.readTree(produced);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void writeProblem(HttpServletResponse response, int status, String type, String title,
                                     String detail, String instance) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"" + type + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"instance\":\"" + instance + "\"}");
    }

    /** Requete dont le corps, deja lu pour etre hache, est rejoue au controleur. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // lecture synchrone uniquement
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
