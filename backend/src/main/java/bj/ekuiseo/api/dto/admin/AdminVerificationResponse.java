package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite, GET /api/v1/admin/verifications?status=...
 *
 * @param reviewedAt      date de la decision (null tant que le dossier est PENDING)
 * @param rejectionReason motif du refus (null sinon)
 * @param reviewedBy      identifiant de l administrateur ayant tranche (null si PENDING)
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
        UUID reviewedBy
) {
}
