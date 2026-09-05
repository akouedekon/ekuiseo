package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reprise des remboursements en attente (echec reseau, redemarrage entre la decision
 * et l execution) et expiration des abonnements jamais payes.
 */
@Component
public class PaymentHousekeepingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentHousekeepingScheduler.class);

    private final RefundService refundService;
    private final SubscriptionService subscriptionService;

    public PaymentHousekeepingScheduler(RefundService refundService, SubscriptionService subscriptionService) {
        this.refundService = refundService;
        this.subscriptionService = subscriptionService;
    }

    /** Toutes les 5 minutes : remboursements demandes depuis plus de 2 minutes et toujours en attente. */
    @Scheduled(fixedRate = 300_000, initialDelay = 90_000)
    public void retryRefunds() {
        int n = refundService.retryPending(Instant.now().minus(2, ChronoUnit.MINUTES));
        if (n > 0) {
            log.info("Reprise de {} remboursement(s) en attente", n);
        }
    }

    /** Toutes les 10 minutes : abonnements PENDING_PAYMENT de plus de 30 minutes -> CANCELLED. */
    @Scheduled(fixedRate = 600_000, initialDelay = 120_000)
    public void expirePendingSubscriptions() {
        int n = subscriptionService.expireStalePending(Instant.now().minus(30, ChronoUnit.MINUTES));
        if (n > 0) {
            log.info("{} abonnement(s) jamais paye(s) expire(s)", n);
        }
    }
}
