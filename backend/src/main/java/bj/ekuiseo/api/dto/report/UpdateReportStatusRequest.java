package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/v1/admin/reports/{id}. Equivalent, en plus cible, de POST .../resolve (conserve).
 * {@code resolutionNote} est exigee par le service pour passer RESOLVED ou DISMISSED
 * (constat F552) ; ignoree pour IN_REVIEW.
 */
public record UpdateReportStatusRequest(
        @NotNull ReportStatus status,
        String resolutionNote
) {
}
