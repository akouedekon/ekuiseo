package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.PushSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.push.PushSubscriptionRequest;
import bj.ekuiseo.api.repository.PushSubscriptionRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Abonnements Web Push de l utilisateur connecte (V20) : enregistrement (upsert par endpoint),
 * retrait, plafond de {@value #MAX_PER_USER} appareils par compte (le plus ancien est evince).
 *
 * <p>L endpoint est unique en base : si un autre compte se connecte sur le meme navigateur et
 * s abonne, l abonnement change de proprietaire (le navigateur ne porte qu un abonnement, et
 * c est bien le compte connecte qui doit recevoir ses notifications).</p>
 */
@Service
public class PushSubscriptionService {

    static final int MAX_PER_USER = 3;

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final UserRepository userRepository;

    public PushSubscriptionService(PushSubscriptionRepository pushSubscriptionRepository, UserRepository userRepository) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void subscribe(UUID userId, PushSubscriptionRequest req, String userAgent) {
        String endpoint = normalizeEndpoint(req.endpoint());
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        PushSubscription subscription = pushSubscriptionRepository.findByEndpoint(endpoint).orElse(null);
        if (subscription == null) {
            List<PushSubscription> existing = pushSubscriptionRepository.findByUserIdOrderByCreatedAtAsc(userId);
            for (int i = 0; i <= existing.size() - MAX_PER_USER; i++) {
                pushSubscriptionRepository.delete(existing.get(i));
            }
            subscription = PushSubscription.builder().user(user).endpoint(endpoint).build();
        } else {
            subscription.setUser(user);
        }
        subscription.setP256dh(req.keys().p256dh().trim());
        subscription.setAuth(req.keys().auth().trim());
        subscription.setUserAgent(truncate(userAgent, 200));
        subscription.setFailures(0);
        subscription.setLastUsedAt(Instant.now());
        pushSubscriptionRepository.save(subscription);
    }

    /** Retrait silencieux : un endpoint inconnu ou appartenant a un autre compte ne change rien (idempotent). */
    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        pushSubscriptionRepository.deleteByUserIdAndEndpoint(userId, normalizeEndpoint(endpoint));
    }

    /** Seul un endpoint HTTPS a un sens : le service push d un navigateur ne s expose jamais autrement. */
    static String normalizeEndpoint(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        if (!e.toLowerCase(Locale.ROOT).startsWith("https://") || e.length() < 12) {
            throw new BadRequestException("Endpoint d abonnement push invalide");
        }
        return e;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
