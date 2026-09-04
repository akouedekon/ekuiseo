package bj.ekuiseo.api.dto.report;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Signalement d'un utilisateur OU d'un trajet : exactement l'un des deux champs
 * cible doit etre renseigne (voir {@link #isValid()}, verifie a la fois ici et
 * en base par la contrainte chk_reports_target, migration V2).
 */
public record CreateReportRequest(
        UUID reportedUserId,
        UUID reportedTripId,
        @NotBlank String reasonCode,
        String details
) {
    @AssertTrue(message = "Exactement une cible (reportedUserId OU reportedTripId) doit etre renseignee")
    public boolean isValid() {
        return (reportedUserId != null) ^ (reportedTripId != null);
    }
}
