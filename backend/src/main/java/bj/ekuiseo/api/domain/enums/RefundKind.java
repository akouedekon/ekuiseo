package bj.ekuiseo.api.domain.enums;

/** Nature d un remboursement (V26) : integral, ou partiel (bareme d annulation tardive, traite a la main : l API de l agregateur ne rembourse que le montant total). */
public enum RefundKind {
    FULL,
    PARTIAL
}
