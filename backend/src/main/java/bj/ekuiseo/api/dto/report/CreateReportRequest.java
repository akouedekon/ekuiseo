package bj.ekuiseo.api.dto.report;

import bj.ekuiseo.api.domain.enums.ReportReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Signalement d'un utilisateur OU d'un trajet : exactement l'un des deux champs
 * cible doit etre renseigne (voir {@link #isValid()}, verifie a la fois ici et
 * en base par la contrainte chk_reports_target, migration V2). Le motif est type
 * (constat F551) : une valeur inconnue est refusee en 400 par Jackson, plus jamais
 * repliee silencieusement sur OTHER. {@code details} est borne comme le champ du
 * front (ReportDialog, 500 caracteres).
 */
public record CreateReportRequest(
        UUID reportedUserId,
        UUID reportedTripId,
        @NotNull ReportReason reasonCode,
        @Size(max = 500) String details
) {
    @AssertTrue(message = "Exactement une cible (reportedUserId OU reportedTripId) doit etre renseignee")
    public boolean isValid() {
        return (reportedUserId != null) ^ (reportedTripId != null);
    }
}
