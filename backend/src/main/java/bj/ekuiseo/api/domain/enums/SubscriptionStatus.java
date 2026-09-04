package bj.ekuiseo.api.domain.enums;

/** Etat de l'abonnement conducteur (regle metier n.11 : 2000 FCFA/mois, commission ramenee a 0%). */
public enum SubscriptionStatus {
    PENDING_PAYMENT,
    ACTIVE,
    EXPIRED,
    CANCELLED
}
