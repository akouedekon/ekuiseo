package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;

import java.time.Instant;

public record IdentityVerificationResponse(
        IdentityVerificationStatus status,
        IdentityDocumentType documentType,
        Instant submittedAt,
        Instant reviewedAt,
        String rejectionReason
) {
}
