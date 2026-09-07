package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuditLogMapper {
    /** actorName est resolu par lot dans AuditService (une requete pour toute la page). */
    @Mapping(target = "actorName", ignore = true)
    AuditLogResponse toResponse(AuditLog auditLog);
}
