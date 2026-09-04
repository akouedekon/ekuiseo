package bj.ekuiseo.api.dto.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Etat d'un paiement pour sondage cote front (GET /api/v1/payments/{paymentId})
 * en attendant le webhook Kkiapay. {@code status} et {@code provider} sont des
 * chaines plutot que les enums internes {@link bj.ekuiseo.api.domain.enums.PaymentStatus}
 * / {@link bj.ekuiseo.api.domain.enums.PaymentChannel} : le vocabulaire expose au
 * front (PENDING/PROCESSING/SUCCEEDED/FAILED/EXPIRED, MTN_MOMO/MOOV_MONEY/CELTIIS_CASH)
 * ne correspond pas terme a terme au vocabulaire interne (voir PaymentService pour
 * le mappage). {@code amount} est le montant reellement charge par CE paiement
 * (l'acompte en MOMO_DEPOSIT, la totalite en MOMO_FULL - regle metier n.21),
 * jamais {@code booking.amount}.
 */
public record PaymentStatusResponse(
        UUID paymentId,
        UUID bookingId,
        String transactionRef,
        String provider,
        String status,
        long amount,
        String instruction,
        Instant updatedAt
) {
}
