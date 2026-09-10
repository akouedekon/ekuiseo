package bj.ekuiseo.api.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/bookings/{id}/contest-driver-no-show (V25) : le conducteur conteste l absence
 * declaree par le passager. Sa version est obligatoire : c est elle que la moderation lit.
 */
public record ContestNoShowRequest(
        @NotBlank(message = "Expliquez a la moderation ce qui s est passe")
        @Size(max = 1000) String details
) {
}
