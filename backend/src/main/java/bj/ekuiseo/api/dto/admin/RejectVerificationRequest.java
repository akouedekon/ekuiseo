package bj.ekuiseo.api.dto.admin;

import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/verifications/{id}/reject. Le motif est optionnel cote front (bouton "rejeter" sans commentaire possible). */
public record RejectVerificationRequest(
        @Size(max = 500) String reason
) {
}
