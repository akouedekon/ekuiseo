package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.OtpCodeRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;

/**
 * Purges nocturnes des donnees a duree de vie limitee (constats F120/F516/F553/F525),
 * mise en oeuvre technique des durees declarees dans docs/CONFORMITE.md, section 3.2 :
 * <ul>
 *   <li>{@code otp_codes} expires depuis plus de {@code ekuiseo.retention.otp-hours} (24 h) ;</li>
 *   <li>{@code notifications} creees il y a plus de {@code ekuiseo.retention.notifications-days} (180 j) ;</li>
 *   <li>{@code messages} des trajets partis depuis plus de {@code ekuiseo.retention.messages-days}
 *       (180 j), hors signalement encore ouvert (voir MessageRepository), puis les conversations
 *       ainsi videes ;</li>
 *   <li>{@code search_alerts} : desactivation des alertes dont la fenetre est passee, suppression
 *       des alertes inactives depuis plus de {@code ekuiseo.retention.search-alerts-days} (90 j).</li>
 * </ul>
 * Chaque purge tourne dans sa propre transaction et sous son propre try/catch : l echec de
 * l une n annule ni ne bloque les autres, et le nombre de lignes touchees est journalise.
 * Les traces de recherche ont leur propre purge (SearchEventRetentionScheduler).
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final OtpCodeRepository otpCodeRepository;
    private final NotificationRepository notificationRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final SearchAlertRepository searchAlertRepository;
    private final TransactionTemplate transaction;
    private final long otpRetentionHours;
    private final int notificationsRetentionDays;
    private final int messagesRetentionDays;
    private final int searchAlertsRetentionDays;

    public RetentionScheduler(OtpCodeRepository otpCodeRepository, NotificationRepository notificationRepository,
                              MessageRepository messageRepository, ConversationRepository conversationRepository,
                              SearchAlertRepository searchAlertRepository, PlatformTransactionManager transactionManager,
                              @Value("${ekuiseo.retention.otp-hours:24}") long otpRetentionHours,
                              @Value("${ekuiseo.retention.notifications-days:180}") int notificationsRetentionDays,
                              @Value("${ekuiseo.retention.messages-days:180}") int messagesRetentionDays,
                              @Value("${ekuiseo.retention.search-alerts-days:90}") int searchAlertsRetentionDays) {
        this.otpCodeRepository = otpCodeRepository;
        this.notificationRepository = notificationRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.searchAlertRepository = searchAlertRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.otpRetentionHours = otpRetentionHours;
        this.notificationsRetentionDays = notificationsRetentionDays;
        this.messagesRetentionDays = messagesRetentionDays;
        this.searchAlertsRetentionDays = searchAlertsRetentionDays;
    }

    /** Chaque nuit a 03:30 (heure du serveur), apres la recurrence (03:00) et les traces de recherche (03:15). */
    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        purgeAll(Instant.now());
    }

    /** Toutes les purges a un instant donne (separe pour les tests) ; renvoie le total de lignes touchees. */
    public int purgeAll(Instant now) {
        int total = 0;
        total += purge("codes de connexion expires",
                () -> otpCodeRepository.deleteByExpiresAtBefore(now.minus(otpRetentionHours, ChronoUnit.HOURS)));
        total += purge("notifications de plus de " + notificationsRetentionDays + " jours",
                () -> notificationRepository.deleteByCreatedAtBefore(now.minus(notificationsRetentionDays, ChronoUnit.DAYS)));
        Instant messagesCutoff = now.minus(messagesRetentionDays, ChronoUnit.DAYS);
        total += purge("messages de trajets partis depuis plus de " + messagesRetentionDays + " jours",
                () -> messageRepository.deleteForTripsDepartedBefore(messagesCutoff));
        total += purge("conversations vides",
                () -> conversationRepository.deleteEmptyForTripsDepartedBefore(messagesCutoff));
        total += purge("alertes de recherche dont la date est passee (desactivees)",
                () -> searchAlertRepository.deactivateExpired(LocalDate.ofInstant(now, Tz.BENIN)));
        total += purge("alertes de recherche inactives depuis plus de " + searchAlertsRetentionDays + " jours",
                () -> searchAlertRepository.deleteInactiveCreatedBefore(now.minus(searchAlertsRetentionDays, ChronoUnit.DAYS)));
        return total;
    }

    /** Une transaction et un try/catch par purge : un echec est journalise, jamais propage. */
    private int purge(String label, IntSupplier action) {
        try {
            Integer count = transaction.execute(status -> action.getAsInt());
            int n = count == null ? 0 : count;
            if (n > 0) {
                log.info("Retention : {} ligne(s) - {}", n, label);
            }
            return n;
        } catch (RuntimeException ex) {
            log.error("Retention : echec de la purge '{}'", label, ex);
            return 0;
        }
    }
}
