package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.TooManyRequestsException;
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
 */
@Component
public class OtpRateLimiter {

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
                throw new TooManyRequestsException(
                        "Trop de demandes de code pour ce numero, reessayez dans quelques minutes.");
            }
            timestamps.addLast(now);
        }
    }

    @Scheduled(fixedRate = 600_000)
    void cleanup() {
        long now = System.currentTimeMillis();
        history.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            Long last;
            synchronized (d) {
                last = d.peekLast();
            }
            return last == null || now - last > IDLE_ENTRY_TTL_MILLIS;
        });
    }
}
