package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportStatus;
import jakarta.validation.constraints.NotNull;

public record ResolveReportRequest(
        @NotNull ReportStatus status,
        String resolutionNote
) {
}
