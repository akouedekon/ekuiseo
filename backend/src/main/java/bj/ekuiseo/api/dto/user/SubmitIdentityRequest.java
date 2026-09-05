package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/me/identity. Le televersement de la photo du document reste un
 * TODO documente (voir IdentityVerification, README) : seul le numero du
 * document est enregistre ici.
 */
public record SubmitIdentityRequest(
        @NotNull IdentityDocumentType documentType,
        @NotBlank @Size(max = 100) String documentNumber
) {
}
