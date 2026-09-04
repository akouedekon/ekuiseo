package bj.ekuiseo.api.domain.enums;

/**
 * Mode de reglement d'une reservation (regle metier n.21, migration V7).
 * Determine comment {@code Booking.amount} se decompose entre
 * {@code depositAmount} (preleve en ligne maintenant, via Kkiapay) et
 * {@code balanceDueOnBoard} (regle en especes au conducteur pendant le trajet).
 */
public enum PaymentMethod {
    /**
     * Mode par defaut : seul l'acompte (voir FeePolicy#computeDepositAmount) est
     * preleve en ligne ; le solde est regle en especes au conducteur a bord.
     */
    MOMO_DEPOSIT,
    /**
     * La totalite du prix est prelevee en ligne (ancien comportement, toujours
     * propose pour un passager qui prefere ne rien regler en especes) :
     * depositAmount = amount, balanceDueOnBoard = 0.
     */
    MOMO_FULL,
    /**
     * Aucun paiement en ligne : depositAmount = 0, balanceDueOnBoard = amount. La
     * plateforme ne percoit rien pour cette reservation (ni acompte, ni
     * commission) - voir README, "ce mode ne doit etre ouvert qu'aux conducteurs
     * de confiance, sinon la commission est contournable".
     */
    CASH;

    /** {@code true} pour les deux modes qui impliquent un paiement Kkiapay (deposit ou total). */
    public boolean isMobileMoney() {
        return this != CASH;
    }
}
