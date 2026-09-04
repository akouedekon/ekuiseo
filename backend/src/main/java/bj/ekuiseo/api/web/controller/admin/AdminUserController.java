package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.dto.admin.SuspendUserRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Gestion des utilisateurs cote back-office : recherche, suspension, reactivation, verification d'identite. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Utilisateurs", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;

    public AdminUserController(AdminUserService adminUserService, CurrentUser currentUser) {
        this.adminUserService = adminUserService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Rechercher des utilisateurs", description = "Recherche libre sur nom/prenom/telephone/e-mail ; liste a plat, plafonnee a 100 resultats (pas de pagination, voir AdminUserService).")
    @GetMapping
    public List<AdminUserResponse> search(@RequestParam(defaultValue = "") String q) {
        return adminUserService.search(q);
    }

    @Operation(summary = "Suspendre un utilisateur")
    @PostMapping("/{id}/suspend")
    public AdminUserResponse suspend(@PathVariable UUID id, @Valid @RequestBody SuspendUserRequest req) {
        return adminUserService.suspend(currentUser.id(), id, req.reason());
    }

    @Operation(summary = "Reactiver un utilisateur suspendu")
    @PostMapping("/{id}/activate")
    public AdminUserResponse activate(@PathVariable UUID id) {
        return adminUserService.activate(currentUser.id(), id);
    }

    @Operation(summary = "Reactiver un utilisateur suspendu (alias)", description = "Meme effet que /activate ; nom attendu par le contrat front (extended.ts).")
    @PostMapping("/{id}/reinstate")
    public AdminUserResponse reinstate(@PathVariable UUID id) {
        return adminUserService.activate(currentUser.id(), id);
    }

    @Operation(summary = "Valider la verification d'identite", description = "Suppose qu'une verification manuelle hors-ligne a deja eu lieu (aucun sous-systeme de stockage de documents d'identite n'est implemente, voir README). Voir aussi /api/v1/admin/verifications pour la file de moderation dediee.")
    @PostMapping("/{id}/verify-identity")
    public AdminUserResponse verifyIdentity(@PathVariable UUID id) {
        return adminUserService.verifyIdentity(currentUser.id(), id);
    }
}
