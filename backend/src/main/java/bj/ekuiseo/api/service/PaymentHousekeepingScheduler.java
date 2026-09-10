package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reprise des remboursements en attente (echec reseau, redemarrage entre la decision
 * et l execution) et expiration des abonnements jamais payes. Chaque job absorbe et
 * journalise ses exceptions (constat F128).
 */
@Component
public class PaymentHousekeepingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentHousekeepingScheduler.class);

    private final RefundService refundService;
    private final SubscriptionService subscriptionService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    public PaymentHousekeepingScheduler(RefundService refundService, SubscriptionService subscriptionService,
                                        PaymentService paymentService, IdempotencyService idempotencyService) {
        this.refundService = refundService;
        this.subscriptionService = subscriptionService;
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    /** Toutes les heures : cles d idempotence de plus de 24 h supprimees (contrat A.1, V26). */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 300_000)
    public void purgeIdempotencyKeys() {
        try {
            int n = idempotencyService.purgeExpired(Instant.now());
            if (n > 0) {
                log.info("{} cle(s) d idempotence purgee(s)", n);
            }
        } catch (RuntimeException ex) {
            log.error("Purge des cles d idempotence : echec de l execution", ex);
        }
    }

    /** Toutes les 5 minutes : paiements INITIATED de plus de 20 minutes sans reservation ni abonnement en attente -> FAILED (F019). */
    @Scheduled(fixedRate = 300_000, initialDelay = 150_000)
    public void failAbandonedPayments() {
        try {
            int n = paymentService.failAbandonedInitiated(Instant.now().minus(PaymentService.ABANDON_DELAY_MINUTES, ChronoUnit.MINUTES));
            if (n > 0) {
                log.info("{} paiement(s) abandonne(s) marque(s) FAILED", n);
            }
        } catch (RuntimeException ex) {
            log.error("Menage des paiements abandonnes : echec de l execution", ex);
        }
    }

    /** Toutes les 5 minutes : remboursements demandes depuis plus de 2 minutes et toujours en attente. */
    @Scheduled(fixedRate = 300_000, initialDelay = 90_000)
    public void retryRefunds() {
        try {
            int n = refundService.retryPending(Instant.now().minus(2, ChronoUnit.MINUTES));
            if (n > 0) {
                log.info("Reprise de {} remboursement(s) en attente", n);
            }
        } catch (RuntimeException ex) {
            log.error("Reprise des remboursements : echec de l execution", ex);
        }
    }

    /** Toutes les 10 minutes : abonnements PENDING_PAYMENT de plus de 30 minutes -> CANCELLED. */
    @Scheduled(fixedRate = 600_000, initialDelay = 120_000)
    public void expirePendingSubscriptions() {
        try {
            int n = subscriptionService.expireStalePending(Instant.now().minus(30, ChronoUnit.MINUTES));
            if (n > 0) {
                log.info("{} abonnement(s) jamais paye(s) expire(s)", n);
            }
        } catch (RuntimeException ex) {
            log.error("Expiration des abonnements impayes : echec de l execution", ex);
        }
    }
}
