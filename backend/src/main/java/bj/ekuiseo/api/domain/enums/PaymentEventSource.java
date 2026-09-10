package bj.ekuiseo.api.domain.enums;

/** Origine d un evenement de paiement (V26, contrat A.5). */
public enum PaymentEventSource {
    WIDGET,
    WEBHOOK,
    SCHEDULER,
    ADMIN,
    SYSTEM
}
