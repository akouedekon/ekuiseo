package bj.ekuiseo.api.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps commun des actions d administration a motif obligatoire : POST /api/v1/admin/users/{id}/revoke-identity et .../anonymize. */
public record AdminReasonRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
