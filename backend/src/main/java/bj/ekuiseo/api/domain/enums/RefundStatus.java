package bj.ekuiseo.api.domain.enums;

/** Machine d etat d un remboursement (V26, contrat A.3) : REQUESTED -> PROCESSING -> SUCCEEDED | FAILED | MANUAL_REVIEW ; FAILED -> PROCESSING (reprise, tant que le plafond de tentatives n est pas atteint) ; MANUAL_REVIEW -> SUCCEEDED (marquage par l administration). Un FAILED dont completed_at est renseigne est definitif (decision d un administrateur). */
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    MANUAL_REVIEW
}
