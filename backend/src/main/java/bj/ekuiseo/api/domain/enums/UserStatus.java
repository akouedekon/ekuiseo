package bj.ekuiseo.api.domain.enums;

public enum UserStatus {
    /** Cree a l inscription, jamais verifie : aucune session possible, purge apres 24 h. */
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    /** Compte anonymise a la demande de l utilisateur ou de l administration (V14) : plus aucune session possible. */
    DELETED
}
