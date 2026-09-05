package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
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
 * a ROLE_ADMIN. Le parametre {@code status} (PENDING par defaut, APPROVED ou REJECTED)
 * selectionne la file a traiter ou l historique des decisions (constat F210).
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

    @Operation(summary = "Verifications par statut", description = "PENDING (defaut) : file a traiter, du plus ancien au plus recent. APPROVED / REJECTED : historique, decision la plus recente en tete.")
    @GetMapping
    public List<AdminVerificationResponse> list(@RequestParam(defaultValue = "PENDING") IdentityVerificationStatus status) {
        return adminVerificationService.listByStatus(status);
    }

    @Operation(summary = "Approuver une verification d'identite", description = "409 si le dossier n est plus PENDING. L utilisateur est prevenu (IDENTITY_APPROVED).")
    @PostMapping("/{id}/approve")
    public void approve(@PathVariable UUID id) {
        adminVerificationService.approve(currentUser.id(), id);
    }

    @Operation(summary = "Rejeter une verification d'identite", description = "409 si le dossier n est plus PENDING. Retire le badge et previent l utilisateur avec le motif (IDENTITY_REJECTED).")
    @PostMapping("/{id}/reject")
    public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectVerificationRequest req) {
        adminVerificationService.reject(currentUser.id(), id, req != null ? req.reason() : null);
    }
}
