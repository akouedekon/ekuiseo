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
    REPORT_RECEIVED,
    /** Remboursement demande (annulation ou paiement recu trop tard), en cours chez l agregateur ou a traiter a la main. */
    PAYMENT_REFUND_PENDING,
    /** Remboursement confirme. */
    PAYMENT_REFUNDED,
    /** Conducteur exclu d un lot de reversement faute de compte mobile money verifie. */
    PAYOUT_ACCOUNT_MISSING,
    /** Le conducteur a modifie l horaire ou le prix d un trajet reserve (annulation gratuite 24 h). */
    TRIP_UPDATED,
    /** Le conducteur a signale l absence du passager au depart. */
    BOOKING_NO_SHOW
}
