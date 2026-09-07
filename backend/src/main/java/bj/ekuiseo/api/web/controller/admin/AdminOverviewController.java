package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminOverviewResponse;
import bj.ekuiseo.api.service.admin.AdminOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Files de travail du back-office (signalements, verifications, reversements, remboursements). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Vue d ensemble", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/overview")
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    public AdminOverviewController(AdminOverviewService adminOverviewService) {
        this.adminOverviewService = adminOverviewService;
    }

    @Operation(summary = "Ce qui attend une action", description = "Signalements ouverts / en examen, dossiers d identite en attente et anciennete du plus vieux, lots de reversement en attente et montant du, remboursements a traiter.")
    @GetMapping
    public AdminOverviewResponse overview() {
        return adminOverviewService.compute();
    }
}
