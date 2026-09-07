package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.report.AdminReportConversationResponse;
import bj.ekuiseo.api.dto.report.AdminReportResponse;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.dto.report.UpdateReportStatusRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Moderation des signalements (regle metier n.15). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Signalements", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final ReportService reportService;
    private final CurrentUser currentUser;

    public AdminReportController(ReportService reportService, CurrentUser currentUser) {
        this.reportService = reportService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Lister les signalements", description = "Filtrable par statut (OPEN/IN_REVIEW/RESOLVED/DISMISSED), du plus recent au plus ancien. Liste a plat, plafonnee (pas de pagination, voir ReportService). priorReportsAgainstTarget = autres signalements visant la meme personne.")
    @GetMapping
    public List<AdminReportResponse> list(@RequestParam(required = false) ReportStatus status) {
        return reportService.listForAdmin(status);
    }

    @Operation(summary = "Changer l'etat d'un signalement (PATCH cible)", description = "Transitions : OPEN -> IN_REVIEW -> RESOLVED | DISMISSED (409 sinon). resolutionNote obligatoire pour RESOLVED / DISMISSED (400 sinon) ; resolvedAt/resolvedBy poses a la cloture seulement. L auteur est prevenu de l issue.")
    @PatchMapping("/{id}")
    public AdminReportResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateReportStatusRequest req) {
        return reportService.updateStatus(currentUser.id(), id, req.status(), req.resolutionNote());
    }

    @Operation(summary = "Traiter un signalement", description = "Memes transitions et memes regles que le PATCH ; conserve pour compatibilite.")
    @PostMapping("/{id}/resolve")
    public ReportResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveReportRequest req) {
        return reportService.resolve(currentUser.id(), id, req);
    }

    @Operation(summary = "Conversations liees a un signalement", description = "Echanges prives entre l auteur et la personne visee (reservation du signalement et reservations partagees). Chaque consultation est journalisee (ADMIN_CONVERSATION_VIEWED).")
    @GetMapping("/{id}/conversations")
    public List<AdminReportConversationResponse> conversations(@PathVariable UUID id) {
        return reportService.conversations(currentUser.id(), id);
    }
}
