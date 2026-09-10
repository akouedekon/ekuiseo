package bj.ekuiseo.api.dto.payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Revenus du conducteur connecte (contrat A.8), GET /api/v1/me/earnings. Tout est calcule en
 * SQL agrege (BookingRepository#getDriverEarnings, DriverPayoutRepository), jamais par
 * chargement des reservations.
 *
 * @param balanceFcfa eligible au reversement, pas encore dans un lot (depart passe depuis 24 h, acompte encaisse)
 * @param awaitingEligibilityFcfa encaisse, trajet non encore parti ou parti depuis moins de 24 h
 * @param inPayoutFcfa lots PENDING / PROCESSING
 * @param paidOutFcfa total regle
 * @param grossFcfa prix total des places vendues (especes comprises)
 * @param commissionFcfa commission prelevee sur les reservations mobile money
 * @param cashCollectedFcfa soldes en especes regles a bord (SETTLED)
 * @param nextPayoutAt prochain lundi 06:00, heure du Benin
 */
public record DriverEarningsResponse(
        long balanceFcfa,
        long awaitingEligibilityFcfa,
        long inPayoutFcfa,
        long paidOutFcfa,
        long grossFcfa,
        long commissionFcfa,
        long cashCollectedFcfa,
        long tripsCompleted,
        long tripsUpcoming,
        long seatsSold,
        BigDecimal ratingAvg,
        int ratingCount,
        long minimumPayoutFcfa,
        Instant nextPayoutAt,
        boolean hasVerifiedPayoutAccount,
        List<Month> byMonth
) {
    /** Six derniers mois civils du Benin, par mois de depart des trajets. */
    public record Month(String month, long grossFcfa, long commissionFcfa, long netFcfa, long cashFcfa, long trips) {
    }
}
