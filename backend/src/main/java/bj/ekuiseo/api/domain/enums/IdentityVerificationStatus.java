package bj.ekuiseo.api.domain.enums;

/** Etat de la verification d'identite d'un utilisateur (regle metier n.19). */
public enum IdentityVerificationStatus {
    NOT_SUBMITTED,
    PENDING,
    APPROVED,
    REJECTED
}
