package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;

import java.time.Instant;

/**
 * Presence d une piece d identite televersee (V20) : jamais le contenu ni le nom du fichier.
 * Portee par IdentityVerificationResponse (utilisateur) et AdminVerificationResponse (back-office).
 */
public record IdentityDocumentSummary(
        IdentityDocumentSide side,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {
}
