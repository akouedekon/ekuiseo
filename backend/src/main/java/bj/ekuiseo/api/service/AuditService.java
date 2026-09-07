package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import bj.ekuiseo.api.mapper.AuditLogMapper;
import bj.ekuiseo.api.repository.AuditLogRepository;
import bj.ekuiseo.api.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Journal d'audit des actions sensibles (annulations, remboursements, actions
 * admin - voir README "Journal d'audit"). Ecriture seule depuis les autres
 * services ; consultation reservee au back-office (AdminAuditController), filtrable
 * par action, acteur, entite et periode (constat F313), avec le nom de l acteur.
 */
@Service
public class AuditService {

    /** Filtres de consultation ; tout champ null est ignore. {@code to} est exclusif. */
    public record Filter(String action, UUID actorId, String entityType, UUID entityId, Instant from, Instant to) {
        public static Filter none() {
            return new Filter(null, null, null, null, null, null);
        }
    }

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository, AuditLogMapper auditLogMapper) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.auditLogMapper = auditLogMapper;
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

    /**
     * Consultation filtree (Specification JPA), du plus recent au plus ancien, avec le nom
     * des acteurs resolu en une requete pour toute la page.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(Filter filter, Pageable pageable) {
        Page<AuditLog> page = auditLogRepository.findAll(toSpecification(filter == null ? Filter.none() : filter), pageable);
        List<UUID> actorIds = page.getContent().stream().map(AuditLog::getActorId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (User u : userRepository.findAllById(actorIds)) {
                names.put(u.getId(), (u.getFirstName() + " " + u.getLastName()).trim());
            }
        }
        return page.map(entry -> auditLogMapper.toResponse(entry)
                .withActorName(entry.getActorId() == null ? null : names.get(entry.getActorId())));
    }

    static Specification<AuditLog> toSpecification(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.action() != null && !f.action().isBlank()) {
                predicates.add(cb.equal(root.get("action"), f.action().trim().toUpperCase()));
            }
            if (f.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), f.actorId()));
            }
            if (f.entityType() != null && !f.entityType().isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), f.entityType().trim()));
            }
            if (f.entityId() != null) {
                predicates.add(cb.equal(root.get("entityId"), f.entityId()));
            }
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            if (query != null) {
                query.orderBy(cb.desc(root.get("createdAt")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
