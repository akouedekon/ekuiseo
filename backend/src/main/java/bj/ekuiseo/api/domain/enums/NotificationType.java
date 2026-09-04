package bj.ekuiseo.api.domain.enums;

/** Type d'evenement notifie a un utilisateur (booking confirmee, paiement, message, etc.). */
public enum NotificationType {
    BOOKING_CONFIRMED,
    BOOKING_CANCELLED,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    NEW_MESSAGE,
    TRIP_REMINDER,
    NEW_REVIEW,
    SEARCH_ALERT_MATCH,
    SUBSCRIPTION_ACTIVATED,
    REPORT_RECEIVED
}
