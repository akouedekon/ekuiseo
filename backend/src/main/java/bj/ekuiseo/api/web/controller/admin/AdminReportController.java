package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.report.AdminReportResponse;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.dto.report.UpdateReportStatusRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

    @Operation(summary = "Lister les signalements", description = "Filtrable par statut (OPEN/IN_REVIEW/RESOLVED/DISMISSED). Liste a plat, plafonnee (pas de pagination, voir ReportService).")
    @GetMapping
    public List<AdminReportResponse> list(@RequestParam(required = false) ReportStatus status) {
        return reportService.listForAdmin(status);
    }

    @Operation(summary = "Changer l'etat d'un signalement (PATCH cible)", description = "Variante ciblee de POST .../resolve, sans note de resolution ; nom attendu par le contrat front (extended.ts).")
    @PatchMapping("/{id}")
    public AdminReportResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateReportStatusRequest req) {
        return reportService.updateStatus(currentUser.id(), id, req.status());
    }

    @Operation(summary = "Traiter un signalement", description = "Change son statut et enregistre une note de resolution.")
    @PostMapping("/{id}/resolve")
    public ReportResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveReportRequest req) {
        return reportService.resolve(currentUser.id(), id, req);
    }
}
