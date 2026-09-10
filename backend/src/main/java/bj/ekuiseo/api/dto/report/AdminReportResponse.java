package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NoShowResolution;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Vue back-office d'un signalement, GET /api/v1/admin/reports?status=...
 *
 * @param reason  {@code Report.reasonCode} (texte libre) mappe vers l'enum front ;
 *                retombe sur OTHER si aucune correspondance exacte (voir ReportService).
 * @param detail  description libre du signalant ; jamais null (chaine vide a defaut, constat F503)
 * @param target  la personne visee : l'utilisateur signale s'il y en a un, sinon le
 *                conducteur du trajet signale (un signalement porte toujours sur un
 *                utilisateur OU un trajet, jamais aucun des deux - voir Report).
 * @param bookingId reservation qui lie le signalant a la cible (V14), null si aucune
 * @param resolutionNote note de cloture (obligatoire pour RESOLVED / DISMISSED, constat F552)
 * @param resolvedBy administrateur ayant clos le signalement (null tant qu il est ouvert ou en examen)
 * @param resolvedAt date de cloture (null tant qu il est ouvert ou en examen)
 * @param priorReportsAgainstTarget autres signalements (tous statuts) visant la meme personne (constat F307)
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
        UUID bookingId,
        String resolutionNote,
        UUID resolvedBy,
        Instant resolvedAt,
        long priorReportsAgainstTarget,
        /** Dossier « conducteur absent » (V25) lie a la reservation ; null pour les autres motifs. */
        NoShowDispute noShowDispute
) {
    public record PersonRef(UUID id, String firstName, String lastName) {
    }

    /**
     * Etat du dossier « conducteur absent » : acompte en jeu, echeance du remboursement automatique,
     * contestation du conducteur (date et version), issue (REFUND_PASSENGER / PAY_DRIVER) et qui l a
     * prise (resolvedBy null = automatique).
     */
    public record NoShowDispute(UUID bookingId, BookingStatus bookingStatus, PaymentMethod paymentMethod,
                                long depositAmountFcfa, Instant refundDueAt, Instant contestedAt, String contestDetails,
                                NoShowResolution resolution, Instant resolvedAt, UUID resolvedBy) {
    }
}
