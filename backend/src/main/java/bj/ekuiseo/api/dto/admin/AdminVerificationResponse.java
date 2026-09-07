package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityDocumentSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite, GET /api/v1/admin/verifications?status=...
 *
 * @param reviewedAt      date de la decision (null tant que le dossier est PENDING)
 * @param rejectionReason motif du refus (null sinon)
 * @param reviewedBy      identifiant de l administrateur ayant tranche (null si PENDING)
 * @param duplicateOfUserIds autres comptes ayant declare la meme piece (meme type, meme numero
 *                           normalise, tous statuts) - a examiner avant d approuver (constat F604)
 * @param documents       pieces televersees (V20) ; le contenu se lit sur
 *                        GET /admin/verifications/{id}/documents/{side}, consultation journalisee
 */
public record AdminVerificationResponse(
        UUID id,
        UUID userId,
        String firstName,
        String lastName,
        String phone,
        IdentityDocumentType documentType,
        String documentNumber,
        Instant submittedAt,
        IdentityVerificationStatus status,
        Instant reviewedAt,
        String rejectionReason,
        UUID reviewedBy,
        List<UUID> duplicateOfUserIds,
        List<IdentityDocumentSummary> documents
) {
}
