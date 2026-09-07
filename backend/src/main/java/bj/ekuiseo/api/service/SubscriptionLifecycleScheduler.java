package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Cycle de vie quotidien des abonnements conducteur (constats F049/F129) : les abonnements
 * echus passent EXPIRED et le conducteur est prevenu ; ceux qui expirent sous 3 jours recoivent
 * un rappel unique. Tourne chaque jour a 06h00 (heure du serveur), et 3 minutes apres le
 * demarrage pour rattraper un redemarrage nocturne.
 */
@Component
public class SubscriptionLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleScheduler.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionLifecycleScheduler(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void daily() {
        run();
    }

    @Scheduled(initialDelay = 180_000, fixedDelay = Long.MAX_VALUE)
    public void onStartup() {
        run();
    }

    void run() {
        Instant now = Instant.now();
        try {
            int expired = subscriptionService.expireEnded(now);
            int notified = subscriptionService.notifyExpiring(now);
            if (expired > 0 || notified > 0) {
                log.info("Abonnements : {} echu(s), {} rappel(s) J-3 envoye(s)", expired, notified);
            }
        } catch (RuntimeException ex) {
            log.error("Cycle de vie des abonnements en echec", ex);
        }
    }
}
