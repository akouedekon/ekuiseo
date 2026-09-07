package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

/**
 * POST /api/v1/trips/{id}/booking-quote : devis calcule sans rien creer en
 * base, pour que le passager voie les montants exacts avant de reserver (voir
 * BookingService#quote, qui applique {@code exactement} la meme FeePolicy que
 * {@link bj.ekuiseo.api.service.BookingService#createBooking} via la
 * factorisation BookingService#computeAmounts - jamais une formule dupliquee).
 *
 * <p>Meme nom de champ {@code paymentMode} que CreateBookingRequest, nullable
 * avec le meme defaut {@code MOMO_DEPOSIT} (regle metier n.21). Memes
 * {@code pickupStopId} / {@code dropoffStopId} (tarif par troncon, constat F122) :
 * le devis et la reservation resolvent les arrets et le prix de la meme facon.</p>
 */
public record BookingQuoteRequest(
        @Min(1) @Max(8) int seats,
        UUID pickupStopId,
        UUID dropoffStopId,
        PaymentMethod paymentMode
) {
}
