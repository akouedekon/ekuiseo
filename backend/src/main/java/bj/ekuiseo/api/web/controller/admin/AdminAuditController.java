package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import bj.ekuiseo.api.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Consultation du journal d'audit des actions sensibles (annulations, remboursements,
 * actions admin). Reserve a ROLE_ADMIN. Servi sous /api/v1/admin/audit (contrat front) et
 * sous l ancien chemin /api/v1/admin/audit-log.
 */
@Tag(name = "Admin - Audit", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping({"/api/v1/admin/audit", "/api/v1/admin/audit-log"})
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Operation(summary = "Consulter le journal d'audit", description = "Trie du plus recent au plus ancien. Filtres optionnels : action (ex. USER_SUSPENDED), actorId, entityType (user, booking, trip, report...), entityId, from/to (instants ISO, to exclusif). actorName est resolu pour chaque entree.")
    @GetMapping
    public Page<AuditLogResponse> list(@RequestParam(required = false) String action,
                                        @RequestParam(required = false) UUID actorId,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) UUID entityId,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(MAX_PAGE_SIZE, size));
        return auditService.search(new AuditService.Filter(action, actorId, entityType, entityId, from, to), pageable);
    }
}
