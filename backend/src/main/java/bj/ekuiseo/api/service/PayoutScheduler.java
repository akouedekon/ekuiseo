package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Constitution automatique du lot de reversement hebdomadaire (V25) : jusqu ici, seul un clic
 * dans le back-office creait les lots, et un conducteur pouvait attendre indefiniment. Chaque
 * lundi a 6 h (heure du Benin, {@code ekuiseo.payout.auto-batch-cron}), les reservations
 * voyagees depuis 24 h, encaissees et non contestees sont regroupees par conducteur
 * ({@link PayoutService#runWeeklyBatch}) ; le conducteur est prevenu que son reversement est
 * prepare. Le virement mobile money lui-meme reste manuel (aucune API de decaissement Kkiapay
 * confirmee) : le back-office le regle depuis /admin/payouts et marque le lot « regle ».
 * Desactivable par {@code ekuiseo.payout.auto-batch-enabled=false} ; un lot deja en cours de
 * constitution (verrou) reporte simplement a la semaine suivante.
 */
@Component
public class PayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);

    private final PayoutService payoutService;
    private final boolean enabled;

    public PayoutScheduler(PayoutService payoutService,
                           @Value("${ekuiseo.payout.auto-batch-enabled:true}") boolean enabled) {
        this.payoutService = payoutService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ekuiseo.payout.auto-batch-cron:0 0 6 * * MON}", zone = "Africa/Porto-Novo")
    public void runWeeklyBatch() {
        runNow();
    }

    /** Execution immediate, separee du declencheur pour les tests ; null si desactive ou verrou pris. */
    public PayoutBatchResultResponse runNow() {
        if (!enabled) {
            log.info("Lot de reversement automatique desactive (ekuiseo.payout.auto-batch-enabled=false)");
            return null;
        }
        try {
            PayoutBatchResultResponse result = payoutService.runWeeklyBatch(null);
            log.info("Lot de reversement hebdomadaire : {} reversement(s) pour {} FCFA, {} conducteur(s) sans compte verifie",
                    result.payoutsCreated(), result.totalAmountFcfa(), result.skipped().size());
            return result;
        } catch (ConflictException ex) {
            log.warn("Lot de reversement hebdomadaire reporte : {}", ex.getMessage());
            return null;
        } catch (RuntimeException ex) {
            log.error("Lot de reversement hebdomadaire : echec de l execution", ex);
            return null;
        }
    }
}
