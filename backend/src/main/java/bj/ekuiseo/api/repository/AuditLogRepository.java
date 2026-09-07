package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Journal d audit ; les filtres du back-office (action, acteur, entite, periode) passent par une Specification (AuditService). */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
