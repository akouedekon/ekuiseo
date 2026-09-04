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
 * avec le meme defaut {@code MOMO_DEPOSIT} (regle metier n.21).</p>
 *
 * <p>Pas de {@code pickupStopId} : comme {@code createBooking} (limitation
 * connue, voir sa javadoc), le prix est aujourd'hui toujours
 * {@code pricePerSeat * seats}, jamais un tarif par troncon -
 * {@code dropoffStopId} n'a donc aucun effet sur le montant, ici comme a la
 * reservation reelle ; il n'est repris ici que pour permettre au front de
 * transmettre le meme contexte qu'a la reservation.</p>
 */
public record BookingQuoteRequest(
        @Min(1) @Max(8) int seats,
        UUID dropoffStopId,
        PaymentMethod paymentMode
) {
}
