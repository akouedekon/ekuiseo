package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param documents pieces televersees (recto, verso, selfie) : presence, type et taille seulement (V20)
 */
public record IdentityVerificationResponse(
        IdentityVerificationStatus status,
        IdentityDocumentType documentType,
        Instant submittedAt,
        Instant reviewedAt,
        String rejectionReason,
        List<IdentityDocumentSummary> documents
) {
}
