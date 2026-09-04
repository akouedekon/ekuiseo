package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AuditLogMapper {
    AuditLogResponse toResponse(AuditLog auditLog);
}
