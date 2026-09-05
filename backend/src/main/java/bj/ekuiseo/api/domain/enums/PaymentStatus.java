package bj.ekuiseo.api.domain.enums;

/**
 * Cycle d un paiement Kkiapay.
 * <ul>
 *   <li>{@code INITIATED} : reference preparee pour le widget, en attente de confirmation.</li>
 *   <li>{@code SUCCEEDED} : verifie aupres de Kkiapay (statut et montant).</li>
 *   <li>{@code FAILED} : refus definitif.</li>
 *   <li>{@code REFUND_PENDING} : remboursement integral demande (annulation, paiement
 *       orphelin), a executer chez Kkiapay hors transaction avec reprise (RefundService).</li>
 *   <li>{@code REFUNDED} : remboursement confirme par Kkiapay ou constate par un admin.</li>
 *   <li>{@code REFUND_MANUAL} : a traiter par le back-office (montant partiel, echec
 *       definitif chez Kkiapay, ou paiement sans identifiant Kkiapay exploitable).</li>
 * </ul>
 */
public enum PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_MANUAL
}
