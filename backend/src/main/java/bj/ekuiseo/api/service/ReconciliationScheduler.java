package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rapprochement quotidien Ekuiseo / fournisseur (contrat A.7) : chaque jour a 04:00, heure du
 * Benin ({@code ekuiseo.reconciliation.cron}), les paiements des 7 derniers jours
 * ({@code ekuiseo.reconciliation.days}) sont re-verifies. Desactivable par
 * {@code ekuiseo.reconciliation.enabled=false}. Toute exception est absorbee et journalisee.
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final boolean enabled;
    private final int days;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   @Value("${ekuiseo.reconciliation.enabled:true}") boolean enabled,
                                   @Value("${ekuiseo.reconciliation.days:7}") int days) {
        this.reconciliationService = reconciliationService;
        this.enabled = enabled;
        this.days = days;
    }

    @Scheduled(cron = "${ekuiseo.reconciliation.cron:0 0 4 * * *}", zone = "Africa/Porto-Novo")
    public void runDaily() {
        if (!enabled) {
            log.info("Rapprochement quotidien desactive (ekuiseo.reconciliation.enabled=false)");
            return;
        }
        try {
            reconciliationService.runScheduled(days);
        } catch (RuntimeException ex) {
            log.error("Rapprochement quotidien : echec de l execution", ex);
        }
    }
}
