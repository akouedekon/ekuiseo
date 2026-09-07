package bj.ekuiseo.api.dto.payment;

/**
 * Vocabulaire client de l etat d un paiement (GET /api/v1/payments/{id}, constats F455/F501),
 * distinct du vocabulaire interne {@link bj.ekuiseo.api.domain.enums.PaymentStatus} :
 * <ul>
 *   <li>{@code PROCESSING} : reference preparee, en attente de la confirmation Kkiapay ;</li>
 *   <li>{@code EXPIRED} : idem mais la reservation a ete annulee ou a expire entre-temps ;</li>
 *   <li>{@code SUCCEEDED} / {@code FAILED} : verdict Kkiapay verifie ;</li>
 *   <li>{@code REFUND_PENDING} : argent encaisse mais reservation perdue ou annulee, remboursement en cours ;</li>
 *   <li>{@code REFUNDED} : remboursement confirme.</li>
 * </ul>
 * Seules ces valeurs sont emises (l ancien {@code PENDING} ne l etait jamais).
 */
public enum PaymentClientStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED
}
