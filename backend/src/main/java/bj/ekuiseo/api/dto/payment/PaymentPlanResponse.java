package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.enums.PaymentMethod;

import java.time.Instant;

/**
 * Detail "paiement en deux temps" attache a une reservation (voir
 * BookingDetailResponse.paymentPlan, GET /api/v1/bookings?expand=trip,paymentPlan) :
 * un paiement fractionne reel (regle metier n.21, migration V7), pas une simple
 * mise en forme d'affichage. {@code depositAmount} et {@code balanceAmount} sont
 * lus directement sur {@code bookings.deposit_amount}/{@code balance_due_on_board},
 * figes a la creation de la reservation - voir FeePolicy#computeDepositAmount pour
 * le calcul de l'acompte et PayoutService#netAmount pour l'impact sur le
 * reversement conducteur.
 *
 * <p>Egalement la forme de reponse de POST /api/v1/trips/{id}/booking-quote (voir
 * BookingService#quote) : un devis calcule AVANT toute reservation, via
 * exactement la meme methode ({@code BookingService#computeAmounts}) que
 * {@code createBooking}, pour que devis et reservation ne puissent jamais
 * diverger - seuls {@code paymentStatus} (toujours {@code "PENDING"}, rien n'existe
 * encore) et {@code depositDueAt} (une estimation ancree sur l'instant de l'appel,
 * pas sur un {@code createdAt} reel) y sont necessairement approximatifs.</p>
 * <ul>
 *   <li>{@code MOMO_DEPOSIT} (defaut) : depositAmount = max(acompte de base,
 *       frais de service), balanceAmount = le reste, regle en especes au
 *       conducteur pendant le trajet.</li>
 *   <li>{@code MOMO_FULL} : depositAmount = totalAmount, balanceAmount = 0
 *       (comportement historique, toujours propose).</li>
 *   <li>{@code CASH} : depositAmount = 0, balanceAmount = totalAmount ; la
 *       plateforme ne percoit rien pour cette reservation (voir README).</li>
 * </ul>
 * {@code paymentStatus} est une vue simplifiee derivee de l'etat de la
 * reservation (PENDING / DEPOSIT_PAID / PAID_IN_FULL / CASH_DUE_ON_BOARD /
 * CANCELLED) - pour l'etat brut du paiement Kkiapay lui-meme (sondage webhook),
 * voir GET /api/v1/payments/{paymentId}.
 */
public record PaymentPlanResponse(
        long totalAmount,
        long depositAmount,
        long balanceAmount,
        long serviceFee,
        PaymentMethod paymentMethod,
        String paymentStatus,
        Instant depositDueAt,
        int freeCancellationHours
) {
}
