package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reglement tacite du solde en especes (contrat A.6, V27) : toutes les 30 minutes, les
 * reservations confirmees par une seule partie, sans litige, dont le trajet est parti depuis
 * 48 h passent SETTLED. Une transaction par reservation, exceptions absorbees et journalisees.
 */
@Component
public class CashSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(CashSettlementScheduler.class);

    private final CashSettlementService cashSettlementService;

    public CashSettlementScheduler(CashSettlementService cashSettlementService) {
        this.cashSettlementService = cashSettlementService;
    }

    @Scheduled(fixedRate = 1_800_000, initialDelay = 240_000)
    public void settleTacitly() {
        Instant now = Instant.now();
        List<UUID> due;
        try {
            due = cashSettlementService.findTacitSettlementsDue(now);
        } catch (RuntimeException ex) {
            log.error("Reglement tacite des especes : lecture des reservations en echec", ex);
            return;
        }
        int settled = 0;
        for (UUID bookingId : due) {
            try {
                if (cashSettlementService.settleTacitly(bookingId, now)) {
                    settled++;
                }
            } catch (RuntimeException ex) {
                log.error("Reglement tacite des especes de la reservation {} en echec", bookingId, ex);
            }
        }
        if (settled > 0) {
            log.info("{} solde(s) en especes regle(s) tacitement", settled);
        }
    }
}
