package bj.ekuiseo.api.domain.enums;

/** Canal d un abonnement push (V24) : navigateur (Web Push, VAPID) ou application native (Firebase Cloud Messaging). */
public enum PushKind {
    WEBPUSH,
    FCM
}
