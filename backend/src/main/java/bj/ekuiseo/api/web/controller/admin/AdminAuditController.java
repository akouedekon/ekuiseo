package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import bj.ekuiseo.api.mapper.AuditLogMapper;
import bj.ekuiseo.api.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consultation du journal d'audit des actions sensibles (annulations, remboursements, actions admin). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Audit", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/audit-log")
public class AdminAuditController {

    private final AuditService auditService;
    private final AuditLogMapper auditLogMapper;

    public AdminAuditController(AuditService auditService, AuditLogMapper auditLogMapper) {
        this.auditService = auditService;
        this.auditLogMapper = auditLogMapper;
    }

    @Operation(summary = "Consulter le journal d'audit", description = "Trie du plus recent au plus ancien.")
    @GetMapping
    public Page<AuditLogResponse> list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditService.list(pageable).map(auditLogMapper::toResponse);
    }
}
