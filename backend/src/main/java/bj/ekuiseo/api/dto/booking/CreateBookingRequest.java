package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

/**
 * POST /api/v1/trips/{id}/bookings. Le champ JSON est {@code paymentMode} (nom
 * exact attendu par le front, distinct du nom de propriete interne
 * {@code Booking.paymentMethod}) - voir BookingQuoteRequest, meme nom de champ,
 * pour que devis et reservation partagent exactement le meme contrat. Nullable :
 * absent, il vaut {@code MOMO_DEPOSIT} par defaut (regle metier n.21), pour ne
 * pas casser les appelants anterieurs a l'introduction du paiement fractionne.
 */
public record CreateBookingRequest(
        @Min(1) @Max(8) int seats,
        UUID pickupStopId,
        UUID dropoffStopId,
        PaymentMethod paymentMode
) {
}
