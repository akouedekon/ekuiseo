package bj.ekuiseo.api.dto.payout;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;

import java.time.Instant;
import java.util.UUID;

/**
 * Vue back-office d'un lot de reversement, GET /api/v1/admin/payouts (voir
 * PayoutService#listAllForAdmin).
 *
 * @param provider operateur mobile money du conducteur : celui de son moyen de
 *                 paiement par defaut s'il en a enregistre un (voir PaymentAccount),
 *                 sinon MTN_MOMO par defaut (operateur le plus repandu au Benin) -
 *                 approximation assumee en l'absence d'operateur trace sur
 *                 DriverPayout lui-meme (seul le numero destinataire y est stocke).
 * @param tripCount nombre de reservations incluses dans ce lot (pas necessairement
 *                   le nombre de trajets distincts, un conducteur pouvant avoir
 *                   plusieurs reservations sur le meme trajet).
 * @param status PENDING/PROCESSING/PAID/FAILED (PAID correspond a SETTLED cote
 *               backend interne, voir PayoutStatus - vocabulaire aligne sur le
 *               contrat front, extended.ts).
 */
public record AdminPayoutResponse(
        UUID id,
        UUID driverId,
        String driverName,
        MobileMoneyOperator provider,
        String phone,
        long amount,
        long tripCount,
        Instant periodStart,
        Instant periodEnd,
        String status,
        Instant paidAt
) {
}
