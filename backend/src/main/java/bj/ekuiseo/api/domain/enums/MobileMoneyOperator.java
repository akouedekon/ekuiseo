package bj.ekuiseo.api.domain.enums;

/**
 * Operateur mobile money d'un moyen de paiement enregistre par l'utilisateur
 * (regle metier n.18, comptes mobile money). Distinct de {@link PaymentProvider}
 * qui designe l'agregateur de paiement (Kkiapay) et non l'operateur telecom.
 */
public enum MobileMoneyOperator {
    MTN_MOMO,
    MOOV_MONEY,
    CELTIIS_CASH
}
