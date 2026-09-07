package bj.ekuiseo.api.dto.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Etat d'un paiement pour sondage cote front (GET /api/v1/payments/{paymentId})
 * en attendant le webhook Kkiapay. {@code status} suit le vocabulaire client
 * {@link PaymentClientStatus} (distinct de {@link bj.ekuiseo.api.domain.enums.PaymentStatus}),
 * {@code provider} celui des operateurs (MTN_MOMO/MOOV_MONEY/CELTIIS_CASH, null pour une
 * carte ou avant confirmation). {@code bookingId} est renseigne pour le paiement d une
 * reservation, {@code subscriptionId} pour celui d un abonnement conducteur (jamais les
 * deux, constat F501). {@code amount} est le montant reellement charge par CE paiement
 * (l'acompte en MOMO_DEPOSIT, la totalite en MOMO_FULL - regle metier n.21), jamais
 * {@code booking.amount}. {@code updatedAt} est la derniere mise a jour reelle du
 * paiement (V17).
 */
public record PaymentStatusResponse(
        UUID paymentId,
        UUID bookingId,
        UUID subscriptionId,
        String transactionRef,
        String provider,
        PaymentClientStatus status,
        long amount,
        String instruction,
        Instant updatedAt
) {
}
