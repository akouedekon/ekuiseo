package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportStatus;

import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID reporterId,
        UUID reportedUserId,
        UUID reportedTripId,
        String reasonCode,
        String details,
        ReportStatus status,
        String resolutionNote,
        Instant createdAt,
        Instant resolvedAt
) {
}
