package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.config.AsyncConfig;
import bj.ekuiseo.api.domain.PushSubscription;
import bj.ekuiseo.api.domain.enums.PushKind;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.PushSubscriptionRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.mail.MailGateway;
import bj.ekuiseo.api.service.push.FcmSender;
import bj.ekuiseo.api.service.push.WebPushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canaux sortants d une notification (constat F107) : e-mail via {@link MailGateway} si
 * l adresse est verifiee et que les preferences l autorisent, SMS via {@link SmsService}
 * pour les seules notifications critiques, selon les preferences, et Web Push
 * ({@link WebPushSender}, V20) vers chaque navigateur abonne si {@code notify_by_push}
 * est vrai (defaut) et que les cles VAPID sont configurees.
 *
 * <p>Appele par {@link NotificationService} apres validation de la transaction metier et
 * sur l executeur {@code notificationExecutor} : la reponse HTTP n attend jamais un relais
 * SMTP, un fournisseur SMS ni un service push. Aucun echec ne remonte : il est journalise,
 * identifiants masques ({@link Masking}). En mode {@code ekuiseo.mail.mode=log} ou
 * {@code ekuiseo.sms.mode=log}, les passerelles journalisent sans envoyer.</p>
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final UserRepository userRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final MailGateway mailGateway;
    private final SmsService smsService;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final WebPushSender webPushSender;
    private final FcmSender fcmSender;

    public NotificationDispatcher(UserRepository userRepository, UserPreferencesRepository userPreferencesRepository,
                                  MailGateway mailGateway, SmsService smsService,
                                  PushSubscriptionRepository pushSubscriptionRepository, WebPushSender webPushSender,
                                  FcmSender fcmSender) {
        this.userRepository = userRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.mailGateway = mailGateway;
        this.smsService = smsService;
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.webPushSender = webPushSender;
        this.fcmSender = fcmSender;
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

        // L e-mail est le seul canal sortant obligatoire du produit (decision du 2026-09-07 : pas
        // de fournisseur SMS). Une notification critique (confirmation, annulation, changement
        // d horaire, rappel) part toujours a l adresse verifiee : elle releve de l execution du
        // contrat ; les autres respectent la preference notify_by_email (vraie par defaut).
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        if ((critical || prefs.isNotifyByEmail()) && user.isEmailVerified() && hasEmail) {
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

        // Web Push : canal complementaire (l e-mail reste la reference), vers chaque appareil
        // abonne. Un abonnement expire (404/410 du service push) est supprime ; un autre echec
        // est compte et journalise, l abonnement conserve.
        if (prefs.isNotifyByPush() && (webPushSender.isEnabled() || fcmSender.isEnabled())) {
            List<PushSubscription> subscriptions = pushSubscriptionRepository.findByUserIdOrderByCreatedAtAsc(userId);
            if (!subscriptions.isEmpty()) {
                NotificationTemplates.Push content = NotificationTemplates.push(type, payload);
                for (PushSubscription subscription : subscriptions) {
                    sendPush(subscription, type, content);
                }
            }
        }
    }

    private void sendPush(PushSubscription subscription, NotificationType type, NotificationTemplates.Push content) {
        try {
            // V24 : jeton FCM de l application native, ou abonnement Web Push du navigateur.
            WebPushSender.Outcome outcome = subscription.getKind() == PushKind.FCM
                    ? fcmSender.send(subscription.getEndpoint(), content)
                    : webPushSender.send(subscription.getEndpoint(), subscription.getP256dh(), subscription.getAuth(), content);
            switch (outcome) {
                case SENT:
                    subscription.setLastUsedAt(Instant.now());
                    subscription.setFailures(0);
                    pushSubscriptionRepository.save(subscription);
                    break;
                case GONE:
                    pushSubscriptionRepository.delete(subscription);
                    log.info("Abonnement push expire supprime pour l utilisateur {}", subscription.getUser().getId());
                    break;
                case FAILED:
                    subscription.setFailures(subscription.getFailures() + 1);
                    pushSubscriptionRepository.save(subscription);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException ex) {
            log.warn("Push {} non envoye (abonnement {})", type, subscription.getId(), ex);
        }
    }
}
