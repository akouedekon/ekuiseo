package bj.ekuiseo.api.domain.enums;

/**
 * Motif d'un signalement, tel qu'expose au back-office. N'est PAS applique comme
 * contrainte stricte en base sur {@code reports.reason_code} (qui reste un texte
 * libre cote entite {@link bj.ekuiseo.api.domain.Report}, pour ne pas casser une
 * valeur deja enregistree par un appelant qui n'utiliserait pas exactement ces
 * libelles) : le mappage vers cet enum retombe sur {@link #OTHER} si la valeur
 * stockee ne correspond a aucune de ces constantes (voir AdminReportMapper).
 */
public enum ReportReason {
    NO_SHOW,
    DANGEROUS_DRIVING,
    HARASSMENT,
    FRAUD,
    VEHICLE_MISMATCH,
    OTHER
}
