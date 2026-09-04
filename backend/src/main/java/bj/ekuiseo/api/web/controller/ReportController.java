package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.report.CreateReportRequest;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Signalement d'un utilisateur ou d'un trajet (regle metier n.15). */
@Tag(name = "Signalements", description = "Signaler un utilisateur ou un trajet a la moderation")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final CurrentUser currentUser;

    public ReportController(ReportService reportService, CurrentUser currentUser) {
        this.reportService = reportService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Signaler un utilisateur ou un trajet", description = "Exactement une cible doit etre fournie : reportedUserId OU reportedTripId.")
    @PostMapping
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reportService.create(currentUser.id(), req));
    }
}
