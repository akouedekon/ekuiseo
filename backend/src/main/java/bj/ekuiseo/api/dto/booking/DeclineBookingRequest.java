package bj.ekuiseo.api.dto.booking;

import jakarta.validation.constraints.Size;

/**
 * Corps facultatif de POST /api/v1/bookings/{id}/decline (V19) : motif transmis au passager
 * avec la notification BOOKING_DECLINED. Jamais obligatoire - un conducteur qui refuse sans
 * s expliquer refuse quand meme.
 */
public record DeclineBookingRequest(
        @Size(max = 300, message = "Le motif ne peut pas depasser 300 caracteres")
        String reason
) {
}
