package bj.ekuiseo.api.domain.enums;

/** Nature d un ecart de rapprochement (V26, contrat A.7). UNKNOWN_AT_PROVIDER : transaction presente dans l export de l agregateur mais inconnue d Ekuiseo ; MISSING_AT_PROVIDER : paiement encaisse chez Ekuiseo que l agregateur ne connait pas. */
public enum AnomalyKind {
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    MISSING_AT_PROVIDER,
    UNKNOWN_AT_PROVIDER,
    DUPLICATE_PROVIDER_TX,
    REFUND_MISSING,
    LEDGER_IMBALANCE
}
