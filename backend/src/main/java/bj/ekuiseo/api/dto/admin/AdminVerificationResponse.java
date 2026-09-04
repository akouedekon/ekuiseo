package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;

import java.time.Instant;
import java.util.UUID;

/** File de moderation des verifications d'identite, GET /api/v1/admin/verifications?status=PENDING. */
public record AdminVerificationResponse(
        UUID id,
        UUID userId,
        String firstName,
        String lastName,
        String phone,
        IdentityDocumentType documentType,
        String documentNumber,
        Instant submittedAt,
        IdentityVerificationStatus status
) {
}
