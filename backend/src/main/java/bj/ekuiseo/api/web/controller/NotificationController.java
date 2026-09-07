package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.notification.NotificationResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Notifications", description = "Notifications in-app de l'utilisateur connecte (voir aussi les SMS critiques envoyes en parallele)")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notificationService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mes notifications", description = "Page Spring (page, size ; 50 par defaut), les plus recentes d abord. Sans parametre : la premiere page de 50.")
    @GetMapping
    public Page<NotificationResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return notificationService.listForUser(currentUser.id(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size))));
    }

    @Operation(summary = "Nombre de notifications non lues", description = "{ \"count\": n } pour le badge, sans charger la liste.")
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount(currentUser.id()));
    }

    @Operation(summary = "Marquer une notification comme lue")
    @PostMapping("/{id}/read")
    public void markAsRead(@PathVariable UUID id) {
        notificationService.markAsRead(currentUser.id(), id);
    }

    @Operation(summary = "Marquer toutes mes notifications comme lues")
    @PostMapping("/read-all")
    public void markAllAsRead() {
        notificationService.markAllAsRead(currentUser.id());
    }
}
