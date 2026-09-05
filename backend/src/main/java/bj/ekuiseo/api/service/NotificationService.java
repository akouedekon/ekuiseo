package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Notification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.dto.notification.NotificationResponse;
import bj.ekuiseo.api.mapper.NotificationMapper;
import bj.ekuiseo.api.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Routeur des notifications (constats F107/F212) : la notification in-app est toujours
 * enregistree en base, dans la transaction de l appelant ; les canaux sortants (e-mail,
 * SMS) sont confies a {@link NotificationDispatcher} apres validation de cette
 * transaction et en asynchrone. Un passager ne doit jamais recevoir "votre reservation
 * est confirmee" pour une transaction finalement annulee, et une passerelle en panne ne
 * doit jamais faire echouer l operation metier.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final NotificationDispatcher dispatcher;

    public NotificationService(NotificationRepository notificationRepository, NotificationMapper notificationMapper,
                                NotificationDispatcher dispatcher) {
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
        this.dispatcher = dispatcher;
    }

    /** In-app en base, puis e-mail selon preferences. Voir {@link #notifyCritical} pour les evenements qui doivent aussi partir par SMS. */
    @Transactional
    public void notify(User user, NotificationType type, Map<String, Object> payload) {
        saveInApp(user, type, payload);
        scheduleDispatch(user.getId(), type, payload, false, null);
    }

    /**
     * Notification critique (regle metier n.10 : reservation confirmee, trajet annule,
     * rappel la veille... doivent atteindre le passager meme s il n ouvre pas l application) :
     * in-app, e-mail et SMS selon preferences. Le texte SMS fourni par l appelant prime sur
     * celui du gabarit ; null pour utiliser le gabarit.
     */
    @Transactional
    public void notifyCritical(User user, NotificationType type, Map<String, Object> payload, String smsMessage) {
        saveInApp(user, type, payload);
        scheduleDispatch(user.getId(), type, payload, true, smsMessage);
    }

    /** Variante critique avec le texte SMS du gabarit ({@link NotificationTemplates}). */
    @Transactional
    public void notifyCritical(User user, NotificationType type, Map<String, Object> payload) {
        notifyCritical(user, type, payload, null);
    }

    private void saveInApp(User user, NotificationType type, Map<String, Object> payload) {
        Notification notification = Notification.builder()
                .user(user)
                .type(type)
                .payload(payload)
                .build();
        notificationRepository.save(notification);
    }

    /**
     * Envoi apres commit si une transaction est en cours (registerSynchronization /
     * afterCommit), immediatement sinon. Le payload est copie : la Map de l appelant ne
     * doit pas etre lue depuis un autre fil.
     */
    private void scheduleDispatch(UUID userId, NotificationType type, Map<String, Object> payload,
                                  boolean critical, String smsMessage) {
        Map<String, Object> copy = payload == null ? Map.of() : new HashMap<>(payload);
        Runnable send = () -> {
            try {
                dispatcher.dispatch(userId, type, copy, critical, smsMessage);
            } catch (RuntimeException ex) {
                // Executeur sature ou arrete : la notification in-app est en base, on ne remonte rien.
                log.warn("Notification {} non planifiee pour l utilisateur {}", type, userId, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(UUID userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(notificationMapper::toResponse).toList();
    }

    /** POST /api/v1/notifications/{id}/read. Idempotent (une notification deja lue n'est pas re-marquee). */
    @Transactional
    public void markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification introuvable"));
        if (!notification.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Cette notification ne vous appartient pas");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }
    }

    /** POST /api/v1/notifications/read-all : marquage en masse (UPDATE en une requete, voir NotificationRepository#markAllAsRead). */
    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllAsRead(userId, Instant.now());
    }
}
