package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.notification.NotificationResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Notifications", description = "Notifications in-app de l'utilisateur connecte (voir aussi les SMS critiques envoyes en parallele)")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notificationService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mes notifications")
    @GetMapping
    public List<NotificationResponse> list() {
        return notificationService.listForUser(currentUser.id());
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
