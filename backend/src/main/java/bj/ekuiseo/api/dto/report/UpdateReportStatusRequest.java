package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportStatus;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/admin/reports/{id}. Equivalent, en plus cible, de POST .../resolve (conserve). */
public record UpdateReportStatusRequest(
        @NotNull ReportStatus status
) {
}
