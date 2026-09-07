package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.PaymentMethod;

import java.util.List;

/**
 * Indicateurs de retention et de paiement du back-office, GET /api/v1/admin/stats/retention?days=N
 * (point n.14 de l audit, CLAUDE.md sections 2 et 3, voir AdminRetentionService). Tous les taux
 * sont des fractions entre 0 et 1, {@code null} quand le denominateur est nul (rien a
 * interpreter, ni 0 ni 100 %) ; {@code previous} porte les memes scalaires sur la periode
 * precedente de meme duree, pour que chaque chiffre s interprete par sa variation.
 *
 * <ul>
 *   <li>{@code driverRetentionW1} / {@code driverRetentionW4} : part des conducteurs ayant
 *       publie sur la periode qui republient entre J+1 et J+7, puis entre J+22 et J+28
 *       (fenetres ecoulees seulement).</li>
 *   <li>{@code passengerRetention30d} : part des passagers ayant reserve (place vendue) sur
 *       la periode qui reservent de nouveau sous 30 jours.</li>
 *   <li>{@code dailyModeShare} : part des reservations vendues portant sur un trajet
 *       QUOTIDIEN - la these produit.</li>
 *   <li>{@code activeRecurringTemplates} : navettes ayant eu au moins une occurrence partie
 *       sur la periode ; {@code avgFilledSeatsPerOccurrence} : places vendues en moyenne par
 *       occurrence partie.</li>
 *   <li>{@code bookingToDepositRate} : reservations mobile money dont l acompte a ete
 *       encaisse / reservations mobile money ; {@code expiredBookingShare} : part expiree
 *       faute de paiement sous 20 minutes.</li>
 *   <li>{@code kkiapayFailureByOperator} : tentatives tranchees et echecs par operateur reel
 *       (payments.channel), paiements abandonnes exclus.</li>
 *   <li>{@code paymentMethodShare} : reservations vendues par mode (nombre, volume FCFA) - la
 *       part CASH est celle qui echappe a la commission.</li>
 *   <li>{@code averageBasketFcfa} / {@code seatsPerBooking} : moyennes sur les reservations
 *       vendues.</li>
 * </ul>
 */
public record AdminRetentionResponse(
        int days,
        Double driverRetentionW1,
        Double driverRetentionW4,
        Double passengerRetention30d,
        Double dailyModeShare,
        long activeRecurringTemplates,
        Double avgFilledSeatsPerOccurrence,
        Double bookingToDepositRate,
        Double expiredBookingShare,
        List<OperatorFailure> kkiapayFailureByOperator,
        List<PaymentMethodShare> paymentMethodShare,
        Double averageBasketFcfa,
        Double seatsPerBooking,
        Scalars previous
) {
    /** Operateur reel (MTN, MOOV, CELTIIS, CARD ou UNKNOWN avant verification). */
    public record OperatorFailure(String operator, long attempts, long failures) {
    }

    public record PaymentMethodShare(PaymentMethod method, long count, long amountFcfa) {
    }

    /** Memes scalaires que la periode courante, sur la periode precedente de meme duree. */
    public record Scalars(
            Double driverRetentionW1,
            Double driverRetentionW4,
            Double passengerRetention30d,
            Double dailyModeShare,
            long activeRecurringTemplates,
            Double avgFilledSeatsPerOccurrence,
            Double bookingToDepositRate,
            Double expiredBookingShare,
            Double averageBasketFcfa,
            Double seatsPerBooking
    ) {
    }
}
