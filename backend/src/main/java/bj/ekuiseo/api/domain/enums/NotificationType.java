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
    BOOKING_NO_SHOW,
    /** Verification d identite validee par l administration (lot 1.4). */
    IDENTITY_APPROVED,
    /** Verification d identite refusee, avec motif. */
    IDENTITY_REJECTED,
    /** Badge d identite retire par l administration, avec motif. */
    IDENTITY_REVOKED,
    /** Compte suspendu par la moderation, avec motif. */
    ACCOUNT_SUSPENDED,
    /** Issue d un signalement, envoyee a son auteur. */
    REPORT_RESOLVED,
    /** Acompte jamais recu dans le delai : reservation expiree, places liberees (phase 2, F010). */
    BOOKING_EXPIRED,
    /** Abonnement conducteur arrivant a echeance sous 3 jours (F049/F129). */
    SUBSCRIPTION_EXPIRING,
    /** Abonnement conducteur echu : la commission s applique de nouveau. */
    SUBSCRIPTION_EXPIRED,
    /** Reversement vire sur le compte mobile money du conducteur (F133). */
    PAYOUT_SETTLED,
    /** Virement du reversement en echec, motif transmis. */
    PAYOUT_FAILED,
    /** Nouvelle version des conditions d utilisation a accepter (F509). */
    TERMS_UPDATED,
    /** Demande de reservation sur un trajet a accord conducteur (V19) : au conducteur (critique) et au passager (accuse). */
    BOOKING_REQUESTED,
    /** Demande refusee par le conducteur, ou restee sans reponse dans le delai : acompte rembourse integralement. */
    BOOKING_DECLINED,
    /** Au conducteur (critique) : un passager declare qu il n est pas venu au depart (V21) ; sans contestation sous 24 h, l acompte est rembourse (V25). */
    DRIVER_NO_SHOW_REPORTED,
    /** Au passager : le conducteur conteste l absence declaree ; le remboursement est gele, la moderation tranche (V25). */
    NO_SHOW_CONTESTED,
    /** Aux deux parties : issue d une declaration « conducteur absent » (remboursement du passager ou trajet maintenu), V25. */
    NO_SHOW_DISPUTE_RESOLVED,
    /** Au conducteur : un reversement vient d etre constitue (lot hebdomadaire automatique, V25) ; le virement suit. */
    PAYOUT_PREPARED
}
