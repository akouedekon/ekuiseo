package bj.ekuiseo.api.dto.payment;

import java.time.Instant;

/**
 * Sort de l argent encaisse sur une reservation, vu par le passager (V25) : un remboursement
 * demande, en cours chez l agregateur, traite a la main par le back-office, ou effectue.
 * Absent (null) tant qu aucun remboursement n a ete decide.
 *
 * @param status  PENDING (chez Kkiapay, reprise automatique), MANUAL (file du back-office,
 *                sous 5 jours ouvres) ou REFUNDED (confirme)
 * @param amountFcfa montant rembourse ou a rembourser
 */
public record RefundSummaryResponse(
        String status,
        long amountFcfa,
        Instant requestedAt,
        Instant refundedAt
) {
    public static final String PENDING = "PENDING";
    public static final String MANUAL = "MANUAL";
    public static final String REFUNDED = "REFUNDED";
}
