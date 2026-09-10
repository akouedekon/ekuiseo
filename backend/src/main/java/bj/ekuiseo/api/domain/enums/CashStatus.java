package bj.ekuiseo.api.domain.enums;

/** Reglement du solde en especes a bord (V27, contrat A.6) : EXPECTED des que la reservation est confirmee avec un solde a bord ; chaque partie confirme apres le depart ; SETTLED quand les deux confirment, ou une seule sans litige 48 h apres le depart ; DISPUTED ouvre un signalement CASH_DISPUTE. */
public enum CashStatus {
    NOT_APPLICABLE,
    EXPECTED,
    DRIVER_CONFIRMED,
    PASSENGER_CONFIRMED,
    SETTLED,
    DISPUTED
}
