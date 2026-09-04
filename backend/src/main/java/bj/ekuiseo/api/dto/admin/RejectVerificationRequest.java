package bj.ekuiseo.api.dto.admin;

/** POST /api/v1/admin/verifications/{id}/reject. Le motif est optionnel cote front (bouton "rejeter" sans commentaire possible). */
public record RejectVerificationRequest(
        String reason
) {
}
