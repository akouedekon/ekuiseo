package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.config.AsyncConfig;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.mail.MailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Canaux sortants d une notification (constat F107) : e-mail via {@link MailGateway} si
 * l adresse est verifiee et que les preferences l autorisent, SMS via {@link SmsService}
 * pour les seules notifications critiques, selon les preferences.
 *
 * <p>Appele par {@link NotificationService} apres validation de la transaction metier et
 * sur l executeur {@code notificationExecutor} : la reponse HTTP n attend jamais un relais
 * SMTP ou un fournisseur SMS. Aucun echec ne remonte : il est journalise, identifiants
 * masques ({@link Masking}). En mode {@code ekuiseo.mail.mode=log} ou {@code ekuiseo.sms.mode=log},
 * les passerelles journalisent sans envoyer.</p>
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final UserRepository userRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final MailGateway mailGateway;
    private final SmsService smsService;

    public NotificationDispatcher(UserRepository userRepository, UserPreferencesRepository userPreferencesRepository,
                                  MailGateway mailGateway, SmsService smsService) {
        this.userRepository = userRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.mailGateway = mailGateway;
        this.smsService = smsService;
    }

    /**
     * Point d entree asynchrone. L utilisateur et ses preferences sont relus en base (la
     * transaction appelante est validee) plutot que transmis : une entite detachee ne doit
     * pas traverser les fils d execution.
     *
     * @param smsMessage texte SMS impose par l appelant, ou null pour celui du gabarit
     */
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    public void dispatch(UUID userId, NotificationType type, Map<String, Object> payload, boolean critical, String smsMessage) {
        try {
            deliver(userId, type, payload, critical, smsMessage);
        } catch (RuntimeException ex) {
            log.warn("Notification {} non acheminee pour l utilisateur {}", type, userId, ex);
        }
    }

    /** Envoi synchrone (sans executeur), separe pour les tests. */
    void deliver(UUID userId, NotificationType type, Map<String, Object> payload, boolean critical, String smsMessage) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() == UserStatus.DELETED) {
            return;
        }
        UserPreferences prefs = userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> UserPreferences.builder().build());
        NotificationTemplates.Rendered rendered = NotificationTemplates.render(type, payload);

        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        if (prefs.isNotifyByEmail() && user.isEmailVerified() && hasEmail) {
            try {
                mailGateway.send(user.getEmail(), rendered.subject(), rendered.body());
            } catch (RuntimeException ex) {
                log.warn("E-mail {} non envoye a {}", type, Masking.email(user.getEmail()), ex);
            }
        }

        boolean hasPhone = user.getPhone() != null && !user.getPhone().isBlank();
        if (critical && prefs.isNotifyBySms() && hasPhone) {
            String text = smsMessage != null && !smsMessage.isBlank() ? smsMessage : rendered.sms();
            try {
                smsService.sendCritical(user.getPhone(), text);
            } catch (RuntimeException ex) {
                log.warn("SMS critique {} non envoye a {}", type, Masking.phone(user.getPhone()), ex);
            }
        }
    }
}
