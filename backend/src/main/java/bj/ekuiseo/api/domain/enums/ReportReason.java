package bj.ekuiseo.api.domain.enums;

/**
 * Motif d'un signalement. Type des l API (CreateReportRequest, constat F551) : une
 * valeur inconnue est refusee en 400. En base, {@code reports.reason_code} reste une
 * colonne texte, mais verrouillee sur ces constantes par la contrainte
 * chk_reports_reason_code (migration V15) ; aucun repli silencieux sur {@link #OTHER}.
 */
public enum ReportReason {
    NO_SHOW,
    DANGEROUS_DRIVING,
    HARASSMENT,
    FRAUD,
    VEHICLE_MISMATCH,
    /** Litige sur le solde en especes a bord (V27, contrat A.6), ouvert par POST /bookings/{id}/cash/dispute. */
    CASH_DISPUTE,
    OTHER
}
