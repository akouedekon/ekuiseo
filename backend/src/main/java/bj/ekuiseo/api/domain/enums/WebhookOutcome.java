package bj.ekuiseo.api.domain.enums;

/** Sort d un webhook recu (V26, contrat A.5) : RECEIVED = persiste, traitement en cours ; PROCESSED ; DUPLICATE = meme corps deja traite, non retraite ; IGNORED = acquitte sans cible ; REJECTED = signature invalide ; ERROR = traitement en echec (verification non conclusive : l agregateur rejoue). */
public enum WebhookOutcome {
    RECEIVED,
    PROCESSED,
    DUPLICATE,
    IGNORED,
    REJECTED,
    ERROR
}
