package bj.ekuiseo.api.domain.enums;

/** Nature d une ecriture du registre financier (V26, contrat A.4). */
public enum LedgerEntryType {
    PASSENGER_PAYMENT,
    PROVIDER_FEE,
    PLATFORM_COMMISSION,
    DRIVER_SHARE,
    REFUND,
    COMMISSION_REVERSAL,
    DRIVER_SHARE_REVERSAL,
    PAYOUT,
    CASH_ON_BOARD,
    ADJUSTMENT
}
