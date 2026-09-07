package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Limitation de debit des demandes de code par numero, quel que soit le canal
 * (e-mail ou SMS) : empeche qu'un tiers ne fasse spammer une adresse ou un numero,
 * et freine le brute-force. Par defaut 3 demandes / 10 minutes / numero
 * ({@code ekuiseo.sms.otp.rate-limit.*}, nom conserve pour compatibilite).
 * Le 429 porte le delai restant de la fenetre ({@code Retry-After}, constat F542).
 *
 * <p><b>Mono-instance</b> (constats F009/F543) : ce compteur vit en memoire de la JVM,
 * repart de zero a chaque redeploiement et n est pas partage entre replicas. Il ne sert
 * plus que de premier filtre, sans acces base : la limite qui fait foi est comptee sur la
 * table otp_codes par {@link OtpCodeService#issue} (durable, exacte). Le backend doit
 * rester deploye en une seule instance tant qu aucun compteur partage n existe.</p>
 */
@Component
public class OtpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(OtpRateLimiter.class);
    private static final long IDLE_ENTRY_TTL_MILLIS = 3_600_000L;

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentMap<String, Deque<Long>> history = new ConcurrentHashMap<>();

    public OtpRateLimiter(@Value("${ekuiseo.sms.otp.rate-limit.max-requests:3}") int maxRequests,
                          @Value("${ekuiseo.sms.otp.rate-limit.window-minutes:10}") long windowMinutes) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMinutes * 60_000L;
    }

    public void assertNotRateLimited(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = history.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                // La plus ancienne demande de la fenetre sort dans (oldest + window - now) ms.
                long retryAfterSeconds = (timestamps.peekFirst() + windowMillis - now + 999) / 1000;
                throw new TooManyRequestsException(
                        "Trop de demandes de code pour ce numero, reessayez dans quelques minutes.", retryAfterSeconds);
            }
            timestamps.addLast(now);
        }
    }

    @Scheduled(fixedRate = 600_000)
    void cleanup() {
        try {
            long now = System.currentTimeMillis();
            history.entrySet().removeIf(e -> {
                Deque<Long> d = e.getValue();
                Long last;
                synchronized (d) {
                    last = d.peekLast();
                }
                return last == null || now - last > IDLE_ENTRY_TTL_MILLIS;
            });
        } catch (RuntimeException ex) {
            log.error("Purge des compteurs OTP : echec de l execution", ex);
        }
    }
}
