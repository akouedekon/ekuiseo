package bj.ekuiseo.api.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/admin/verifications/{id}/reject. Le motif est obligatoire (constat F310) :
 * il est transmis a l utilisateur (IDENTITY_REJECTED) et journalise, un refus sans
 * explication n est pas exploitable.
 */
public record RejectVerificationRequest(
        @NotBlank(message = "Indiquez le motif du refus") @Size(max = 500) String reason
) {
}
