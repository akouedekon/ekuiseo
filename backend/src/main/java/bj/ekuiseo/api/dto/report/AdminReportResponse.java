package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Vue back-office d'un signalement, GET /api/v1/admin/reports?status=...
 *
 * @param reason  {@code Report.reasonCode} (texte libre) mappe vers l'enum front ;
 *                retombe sur OTHER si aucune correspondance exacte (voir ReportService).
 * @param target  la personne visee : l'utilisateur signale s'il y en a un, sinon le
 *                conducteur du trajet signale (un signalement porte toujours sur un
 *                utilisateur OU un trajet, jamais aucun des deux - voir Report).
 * @param bookingId reservation qui lie le signalant a la cible (V14), null si aucune
 */
public record AdminReportResponse(
        UUID id,
        ReportReason reason,
        ReportStatus status,
        String detail,
        Instant createdAt,
        PersonRef reporter,
        PersonRef target,
        UUID tripId,
        UUID bookingId
) {
    public record PersonRef(UUID id, String firstName, String lastName) {
    }
}
