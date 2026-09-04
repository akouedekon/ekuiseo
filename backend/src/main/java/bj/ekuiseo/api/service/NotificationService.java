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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final SmsService smsService;

    public NotificationService(NotificationRepository notificationRepository, NotificationMapper notificationMapper,
                                SmsService smsService) {
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
        this.smsService = smsService;
    }

    /** Notification en base uniquement (in-app). Voir {@link #notifyCritical} pour les evenements qui doivent aussi partir par SMS. */
    @Transactional
    public void notify(User user, NotificationType type, Map<String, Object> payload) {
        Notification notification = Notification.builder()
                .user(user)
                .type(type)
                .payload(payload)
                .build();
        notificationRepository.save(notification);
    }

    /**
     * Notification en base ET par SMS (regle metier n.10 : au minimum, reservation
     * confirmee, trajet annule, rappel la veille doivent atteindre le passager meme
     * s'il n'ouvre pas l'application). Le SMS est envoye au mieux-effort : un echec
     * d'envoi SMS ne doit jamais faire echouer la transaction metier appelante (ex :
     * confirmation de paiement), donc les exceptions du gateway SMS sont capturees
     * et journalisees ici plutot que propagees.
     */
    @Transactional
    public void notifyCritical(User user, NotificationType type, Map<String, Object> payload, String smsMessage) {
        notify(user, type, payload);
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            return;
        }
        try {
            smsService.sendCritical(user.getPhone(), smsMessage);
        } catch (RuntimeException ex) {
            log.error("Echec d'envoi du SMS critique ({}) a l'utilisateur {}", type, user.getId(), ex);
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
