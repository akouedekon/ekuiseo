package bj.ekuiseo.api.dto.payout;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;

import java.time.Instant;
import java.util.UUID;

/**
 * Vue back-office d'un lot de reversement, GET /api/v1/admin/payouts (voir
 * PayoutService#listAllForAdmin).
 *
 * @param provider operateur du compte mobile money verifie par defaut du conducteur,
 *                 fige au moment du lot (V12). Null uniquement sur les lots anterieurs.
 * @param phone numero de ce compte (jamais le numero de connexion depuis V12).
 * @param tripCount nombre de reservations incluses dans ce lot.
 * @param reversedCount reservations remboursees apres inclusion dans un lot deja traite ;
 *                      {@code reversedAmount} est a deduire du prochain virement.
 * @param status PENDING/PROCESSING/PAID/FAILED (PAID = SETTLED interne).
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
        Instant paidAt,
        long reversedCount,
        long reversedAmount
) {
}
