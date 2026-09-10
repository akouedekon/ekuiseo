package bj.ekuiseo.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Limitation de debit en memoire (regle metier n.14), sans dependance externe
 * (pas de Redis / Bucket4j). Fenetre glissante par cle "prefixe:identifiant",
 * comptant les requetes des N dernieres secondes dans une deque par cle.
 *
 * <p><b>Limites assumees et documentees</b> (voir README "Limitation de debit") :
 * <ul>
 *   <li><b>Mono-instance</b> (constat F009) : l'etat est local a l'instance JVM et repart de
 *       zero a chaque redeploiement. Avec plusieurs replicas derriere un load-balancer,
 *       chaque instance appliquerait sa propre limite (la limite effective globale serait
 *       multipliee par le nombre de replicas). Le backend doit donc rester deploye en une
 *       seule instance ; pour une limite strictement globale, il faudrait un compteur
 *       partage (Redis).</li>
 *   <li>Pour les quotas par IP, la cle est l'adresse du client (X-Real-IP, sinon
 *       dernier element de X-Forwarded-For, sinon adresse socket) : un NAT partage
 *       (plusieurs utilisateurs derriere la meme box/proxy) partage donc le meme quota.</li>
 *   <li>Pour les quotas par utilisateur, la cle est l identifiant porte par le jeton
 *       d acces (lu sans acces base) ; sans jeton valide, on retombe sur l IP.</li>
 * </ul>
 * </p>
 *
 * <p>Quotas par defaut (configurables via application.yml, ekuiseo.rate-limit.*) :
 * <ul>
 *   <li>{@code auth:} /api/v1/auth/** : 20 requetes / 60 s / IP (bourrage d'identifiants, spam).</li>
 *   <li>{@code otp:} /otp/request et /otp/register : 10 / 10 min / IP, en plus du quota auth.</li>
 *   <li>{@code webhook:} /api/v1/payments/kkiapay/webhook : 120 / 60 s / IP (Kkiapay peut retenter).</li>
 *   <li>{@code search:} GET /api/v1/trips/search, /api/v1/geo/search et /api/v1/geo/places :
 *       60 / 60 s / IP (endpoints publics, une requete PostGIS chacun - constats F025/F416).</li>
 *   <li>{@code msg:} POST /api/v1/bookings/{id}/messages : 30 / 10 min / utilisateur (constat F547).</li>
 *   <li>{@code alert:} POST /api/v1/trip-alerts : 10 / 10 min / utilisateur (constat F524).</li>
 *   <li>{@code live:} POST /api/v1/trips/{id}/live/positions : 120 / 60 s / utilisateur (suivi en
 *       direct, V23 : le navigateur envoie au plus une position toutes les 10 s).</li>
 *   <li>{@code live-public:} GET /api/v1/live/{token} : 120 / 60 s / IP (lien public de suivi,
 *       interroge toutes les 10 s par chaque proche derriere un meme NAT).</li>
 * </ul>
 * Toute reponse 429 porte {@code Retry-After} : secondes avant que la plus ancienne requete
 * de la fenetre n en sorte (constat F542).</p>
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final String WEBHOOK_PATH = "/api/v1/payments/kkiapay/webhook";
    private static final String AUTH_PREFIX = "/api/v1/auth/";
    /** Demandes de code : envoi reel d e-mails, enumeration de numeros -> quota propre, plus strict. */
    private static final Set<String> OTP_PATHS = Set.of("/api/v1/auth/otp/request", "/api/v1/auth/otp/register");
    private static final Set<String> SEARCH_PATHS = Set.of("/api/v1/trips/search", "/api/v1/geo/search", "/api/v1/geo/places");
    private static final Pattern MESSAGES_PATH = Pattern.compile("^/api/v1/bookings/[^/]+/messages$");
    private static final String ALERTS_PATH = "/api/v1/trip-alerts";
    /** Rapports d erreur du navigateur (ClientErrorController) : public, donc borne par IP. */
    private static final String CLIENT_ERRORS_PATH = "/api/v1/client-errors";
    /** Positions du conducteur (TripLiveController, V23) : par utilisateur. */
    private static final Pattern LIVE_POSITIONS_PATH = Pattern.compile("^/api/v1/trips/[^/]+/live/positions$");
    /** Suivi public par jeton (TripLiveController, V23) : public, donc borne par IP. */
    private static final Pattern PUBLIC_LIVE_PATH = Pattern.compile("^/api/v1/live/[^/]+$");
    private static final long IDLE_ENTRY_TTL_MILLIS = 3_600_000L; // 1h : purge des cles inactives

    private final int authMaxRequests;
    private final long authWindowMillis;
    private final int webhookMaxRequests;
    private final long webhookWindowMillis;
    private final int otpMaxRequests;
    private final long otpWindowMillis;
    private final int searchMaxRequests;
    private final long searchWindowMillis;
    private final int messageMaxRequests;
    private final long messageWindowMillis;
    private final int alertMaxRequests;
    private final long alertWindowMillis;
    private final int clientErrorsMaxRequests;
    private final long clientErrorsWindowMillis;
    private final int liveMaxRequests;
    private final long liveWindowMillis;
    private final int livePublicMaxRequests;
    private final long livePublicWindowMillis;
    /** Null dans les tests unitaires du filtre : les quotas par utilisateur retombent alors sur l IP. */
    @Nullable
    private final JwtService jwtService;

    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Constructeur de test : quotas auth/webhook explicites, autres quotas par defaut. */
    public RateLimitingFilter(int authMaxRequests, long authWindowSeconds, int webhookMaxRequests, long webhookWindowSeconds) {
        this(authMaxRequests, authWindowSeconds, webhookMaxRequests, webhookWindowSeconds, 10, 600);
    }

    /** Constructeur de test : quotas auth/webhook/otp explicites, quotas search/msg/alert par defaut. */
    public RateLimitingFilter(int authMaxRequests, long authWindowSeconds, int webhookMaxRequests, long webhookWindowSeconds,
                              int otpMaxRequests, long otpWindowSeconds) {
        this(null, authMaxRequests, authWindowSeconds, webhookMaxRequests, webhookWindowSeconds, otpMaxRequests, otpWindowSeconds,
                60, 60, 30, 600, 10, 600, 30, 600, 120, 60, 120, 60);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitingFilter(@Nullable JwtService jwtService,
                              @Value("${ekuiseo.rate-limit.auth.max-requests:20}") int authMaxRequests,
                              @Value("${ekuiseo.rate-limit.auth.window-seconds:60}") long authWindowSeconds,
                              @Value("${ekuiseo.rate-limit.webhook.max-requests:120}") int webhookMaxRequests,
                              @Value("${ekuiseo.rate-limit.webhook.window-seconds:60}") long webhookWindowSeconds,
                              @Value("${ekuiseo.rate-limit.otp.max-requests:10}") int otpMaxRequests,
                              @Value("${ekuiseo.rate-limit.otp.window-seconds:600}") long otpWindowSeconds,
                              @Value("${ekuiseo.rate-limit.search.max-requests:60}") int searchMaxRequests,
                              @Value("${ekuiseo.rate-limit.search.window-seconds:60}") long searchWindowSeconds,
                              @Value("${ekuiseo.rate-limit.message.max-requests:30}") int messageMaxRequests,
                              @Value("${ekuiseo.rate-limit.message.window-seconds:600}") long messageWindowSeconds,
                              @Value("${ekuiseo.rate-limit.alert.max-requests:10}") int alertMaxRequests,
                              @Value("${ekuiseo.rate-limit.alert.window-seconds:600}") long alertWindowSeconds,
                              @Value("${ekuiseo.rate-limit.client-errors.max-requests:30}") int clientErrorsMaxRequests,
                              @Value("${ekuiseo.rate-limit.client-errors.window-seconds:600}") long clientErrorsWindowSeconds,
                              @Value("${ekuiseo.rate-limit.live.max-requests:120}") int liveMaxRequests,
                              @Value("${ekuiseo.rate-limit.live.window-seconds:60}") long liveWindowSeconds,
                              @Value("${ekuiseo.rate-limit.live-public.max-requests:120}") int livePublicMaxRequests,
                              @Value("${ekuiseo.rate-limit.live-public.window-seconds:60}") long livePublicWindowSeconds) {
        this.jwtService = jwtService;
        this.authMaxRequests = authMaxRequests;
        this.authWindowMillis = authWindowSeconds * 1000L;
        this.webhookMaxRequests = webhookMaxRequests;
        this.webhookWindowMillis = webhookWindowSeconds * 1000L;
        this.otpMaxRequests = otpMaxRequests;
        this.otpWindowMillis = otpWindowSeconds * 1000L;
        this.searchMaxRequests = searchMaxRequests;
        this.searchWindowMillis = searchWindowSeconds * 1000L;
        this.messageMaxRequests = messageMaxRequests;
        this.messageWindowMillis = messageWindowSeconds * 1000L;
        this.alertMaxRequests = alertMaxRequests;
        this.alertWindowMillis = alertWindowSeconds * 1000L;
        this.clientErrorsMaxRequests = clientErrorsMaxRequests;
        this.clientErrorsWindowMillis = clientErrorsWindowSeconds * 1000L;
        this.liveMaxRequests = liveMaxRequests;
        this.liveWindowMillis = liveWindowSeconds * 1000L;
        this.livePublicMaxRequests = livePublicMaxRequests;
        this.livePublicWindowMillis = livePublicWindowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return quotaFor(request) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Quota quota = quotaFor(request);
        if (quota == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String key = quota.prefix + quota.subject;
        long retryAfter = acquire(key, quota.max, quota.windowMillis);
        if (retryAfter == 0 && quota.prefix.equals("auth:") && OTP_PATHS.contains(path)) {
            retryAfter = acquire("otp:" + quota.subject, otpMaxRequests, otpWindowMillis);
            if (retryAfter > 0) {
                key = "otp:" + quota.subject;
            }
        }
        if (retryAfter > 0) {
            log.warn("Rate limit depasse pour {} sur {}", key, path);
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.getWriter().write(
                    "{\"type\":\"https://ekuiseo.bj/problems/rate-limited\","
                            + "\"title\":\"Trop de requetes\",\"status\":429,"
                            + "\"detail\":\"Limite de debit atteinte, reessayez dans " + retryAfter + " s.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Quota applicable a la requete, ou null si l endpoint n est pas limite. */
    @Nullable
    private Quota quotaFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.equals(WEBHOOK_PATH)) {
            return new Quota("webhook:", clientIp(request), webhookMaxRequests, webhookWindowMillis);
        }
        if (path.startsWith(AUTH_PREFIX)) {
            return new Quota("auth:", clientIp(request), authMaxRequests, authWindowMillis);
        }
        if ("GET".equals(method) && SEARCH_PATHS.contains(path)) {
            return new Quota("search:", clientIp(request), searchMaxRequests, searchWindowMillis);
        }
        if ("POST".equals(method) && MESSAGES_PATH.matcher(path).matches()) {
            return new Quota("msg:", userOrIp(request), messageMaxRequests, messageWindowMillis);
        }
        if ("POST".equals(method) && path.equals(ALERTS_PATH)) {
            return new Quota("alert:", userOrIp(request), alertMaxRequests, alertWindowMillis);
        }
        if ("POST".equals(method) && path.equals(CLIENT_ERRORS_PATH)) {
            return new Quota("errors:", clientIp(request), clientErrorsMaxRequests, clientErrorsWindowMillis);
        }
        if ("POST".equals(method) && LIVE_POSITIONS_PATH.matcher(path).matches()) {
            return new Quota("live:", userOrIp(request), liveMaxRequests, liveWindowMillis);
        }
        if ("GET".equals(method) && PUBLIC_LIVE_PATH.matcher(path).matches()) {
            return new Quota("live-public:", clientIp(request), livePublicMaxRequests, livePublicWindowMillis);
        }
        return null;
    }

    /**
     * Tente d enregistrer une requete pour la cle. Renvoie 0 si elle est acceptee, sinon le
     * nombre de secondes (arrondi au superieur) avant que la plus ancienne requete de la
     * fenetre n en sorte - valeur de l en-tete Retry-After.
     */
    private long acquire(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= max) {
                long millisLeft = timestamps.peekFirst() + windowMillis - now;
                return Math.max(1, (millisLeft + 999) / 1000);
            }
            timestamps.addLast(now);
            return 0;
        }
    }

    /**
     * Identifiant de l utilisateur porte par le jeton d acces (signature et type verifies,
     * aucun acces base), prefixe "u:" ; sinon l adresse IP. Un jeton invalide n est pas
     * rejete ici : la chaine de securite s en charge plus loin.
     */
    private String userOrIp(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (jwtService != null && header != null && header.startsWith("Bearer ")) {
            try {
                return "u:" + jwtService.extractUserIdFromAccessToken(header.substring(7));
            } catch (RuntimeException ignored) {
                // JwtException ou IllegalArgumentException : jeton invalide, repli sur l IP.
            }
        }
        return clientIp(request);
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
        try {
            long now = System.currentTimeMillis();
            hits.entrySet().removeIf(e -> {
                Deque<Long> d = e.getValue();
                Long last;
                synchronized (d) {
                    last = d.peekLast();
                }
                return last == null || now - last > IDLE_ENTRY_TTL_MILLIS;
            });
        } catch (RuntimeException ex) {
            log.error("Purge des compteurs de debit : echec de l execution", ex);
        }
    }

    private record Quota(String prefix, String subject, int max, long windowMillis) {
    }
}
