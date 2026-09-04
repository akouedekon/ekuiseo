package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminVerificationResponse;
import bj.ekuiseo.api.dto.admin.RejectVerificationRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.admin.AdminVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite (regle metier n.19). Reserve
 * a ROLE_ADMIN. Le parametre {@code status} n'accepte pour l'instant que PENDING
 * (seule valeur utilisee par le front) ; toute autre valeur est ignoree.
 */
@Tag(name = "Admin - Verifications d'identite", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/verifications")
public class AdminVerificationController {

    private final AdminVerificationService adminVerificationService;
    private final CurrentUser currentUser;

    public AdminVerificationController(AdminVerificationService adminVerificationService, CurrentUser currentUser) {
        this.adminVerificationService = adminVerificationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "File d'attente des verifications a traiter")
    @GetMapping
    public List<AdminVerificationResponse> list(@RequestParam(defaultValue = "PENDING") String status) {
        return adminVerificationService.listPending();
    }

    @Operation(summary = "Approuver une verification d'identite")
    @PostMapping("/{id}/approve")
    public void approve(@PathVariable UUID id) {
        adminVerificationService.approve(currentUser.id(), id);
    }

    @Operation(summary = "Rejeter une verification d'identite")
    @PostMapping("/{id}/reject")
    public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectVerificationRequest req) {
        adminVerificationService.reject(currentUser.id(), id, req != null ? req.reason() : null);
    }
}
