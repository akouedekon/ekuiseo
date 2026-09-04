package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge quotidienne des traces de recherche (search_events) au-dela de la duree
 * de conservation configuree (ekuiseo.search-events.retention-days, 180 jours par
 * defaut). C'est la mise en oeuvre technique de la duree declaree dans
 * docs/CONFORMITE.md, section 3.2 : une trace de recherche est une donnee
 * d'usage, utile pour piloter la liquidite sur quelques mois, sans finalite au-dela.
 */
@Component
public class SearchEventRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchEventRetentionScheduler.class);

    private final SearchEventService searchEventService;
    private final int retentionDays;

    public SearchEventRetentionScheduler(SearchEventService searchEventService,
                                         @Value("${ekuiseo.search-events.retention-days:180}") int retentionDays) {
        this.searchEventService = searchEventService;
        this.retentionDays = retentionDays;
    }

    /** Chaque nuit a 03:15 (heure du serveur), hors des pics d'usage. */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpiredSearchEvents() {
        int deleted = searchEventService.purgeOlderThan(retentionDays);
        if (deleted > 0) {
            log.info("{} trace(s) de recherche purgee(s) (conservation : {} jours)", deleted, retentionDays);
        }
    }
}
