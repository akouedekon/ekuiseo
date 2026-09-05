package bj.ekuiseo.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Limitation de debit en memoire (regle metier n.14), sans dependance externe
 * (pas de Redis / Bucket4j). Fenetre glissante par cle "prefixe:adresse IP",
 * comptant les requetes des N dernieres secondes dans une deque par cle.
 *
 * <p><b>Limites assumees et documentees</b> (voir README "Limitation de debit") :
 * <ul>
 *   <li>L'etat est local a l'instance JVM : avec plusieurs replicas derriere un
 *       load-balancer, chaque instance applique sa propre limite (la limite
 *       effective globale est donc multipliee par le nombre de replicas). Pour
 *       une limite strictement globale, il faudrait un compteur partage (Redis).</li>
 *   <li>La cle est l'adresse IP du client (en-tete X-Forwarded-For si present,
 *       sinon l'adresse socket) : un NAT partage (plusieurs utilisateurs derriere
 *       la meme box/proxy) partage donc le meme quota.</li>
 * </ul>
 * </p>
 *
 * <p>Limites par defaut (configurables via application.yml, voir
 * ekuiseo.rate-limit.*) :
 * <ul>
 *   <li>/api/v1/auth/** : 20 requetes / 60 secondes / IP (protege /login, /otp/request, etc.
 *       contre le bourrage d'identifiants et le spam SMS).</li>
 *   <li>/api/v1/payments/kkiapay/webhook : 120 requetes / 60 secondes / IP (Kkiapay peut
 *       retenter un webhook plusieurs fois ; cette limite protege seulement contre un abus
 *       massif, pas contre un usage normal de l'agregateur).</li>
 * </ul>
 * </p>
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final String WEBHOOK_PATH = "/api/v1/payments/kkiapay/webhook";
    private static final String AUTH_PREFIX = "/api/v1/auth/";
    private static final long IDLE_ENTRY_TTL_MILLIS = 3_600_000L; // 1h : purge des cles inactives

    private final int authMaxRequests;
    private final long authWindowMillis;
    private final int webhookMaxRequests;
    private final long webhookWindowMillis;

    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitingFilter(@Value("${ekuiseo.rate-limit.auth.max-requests:20}") int authMaxRequests,
                              @Value("${ekuiseo.rate-limit.auth.window-seconds:60}") long authWindowSeconds,
                              @Value("${ekuiseo.rate-limit.webhook.max-requests:120}") int webhookMaxRequests,
                              @Value("${ekuiseo.rate-limit.webhook.window-seconds:60}") long webhookWindowSeconds) {
        this.authMaxRequests = authMaxRequests;
        this.authWindowMillis = authWindowSeconds * 1000L;
        this.webhookMaxRequests = webhookMaxRequests;
        this.webhookWindowMillis = webhookWindowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith(AUTH_PREFIX) || path.equals(WEBHOOK_PATH));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isWebhook = path.equals(WEBHOOK_PATH);
        int max = isWebhook ? webhookMaxRequests : authMaxRequests;
        long windowMillis = isWebhook ? webhookWindowMillis : authWindowMillis;
        String key = (isWebhook ? "webhook:" : "auth:") + clientIp(request);

        if (!tryAcquire(key, max, windowMillis)) {
            log.warn("Rate limit depasse pour {} sur {}", key, path);
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"type\":\"https://ekuiseo.bj/problems/rate-limited\","
                            + "\"title\":\"Trop de requetes\",\"status\":429,"
                            + "\"detail\":\"Limite de debit atteinte, reessayez plus tard.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryAcquire(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= max) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Adresse du client derriere la chaine nginx (hote) -> Caddy -> backend.
     * <ul>
     *   <li>X-Real-IP d abord : pose par nginx depuis {@code $remote_addr} (ou par le
     *       Caddyfile principal depuis {@code {remote_host}}), donc non forgeable par le
     *       client ; ignore si elle contient une liste (valeur recopiee d un XFF).</li>
     *   <li>Sinon le DERNIER element de X-Forwarded-For : c est celui ajoute par le proxy
     *       de confiance ; le premier est controle par le client ($proxy_add_x_forwarded_for).</li>
     *   <li>Sinon l adresse distante (acces direct, developpement).</li>
     * </ul>
     */
    static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && !realIp.contains(",")) {
            return realIp.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    /** Purge les cles inactives depuis plus d'une heure pour eviter une fuite memoire lente. */
    @Scheduled(fixedRate = 600_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            Long last;
            synchronized (d) {
                last = d.peekLast();
            }
            return last == null || now - last > IDLE_ENTRY_TTL_MILLIS;
        });
    }
}
