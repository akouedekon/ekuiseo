package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit des actions sensibles (annulations, remboursements, actions
 * admin - voir README "Journal d'audit"). Ecriture seule depuis les autres
 * services ; consultation reservee au back-office (AdminAuditController).
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Toujours executee dans sa propre transaction (REQUIRES_NEW) : un audit ne doit
     * jamais etre perdu par un rollback de la transaction metier appelante, ni au
     * contraire faire echouer cette derniere si l'ecriture d'audit posait probleme.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> details) {
        auditLogRepository.save(AuditLog.builder()
                .actorId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(details)
                .build());
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> list(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
