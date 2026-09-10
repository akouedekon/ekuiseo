package bj.ekuiseo.api.dto.push;

import jakarta.validation.Valid;
import bj.ekuiseo.api.domain.enums.PushKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/me/push-subscriptions : forme standard {@code PushSubscription.toJSON()} du
 * navigateur ({@code endpoint}, {@code keys.p256dh}, {@code keys.auth}). {@code expirationTime}
 * est ignore.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 4000) String endpoint,
        @Valid Keys keys,
        /** WEBPUSH par defaut ; FCM pour l application native (V24) : {@code endpoint} porte alors le jeton. */
        PushKind kind
) {
    public PushKind kindOrDefault() {
        return kind == null ? PushKind.WEBPUSH : kind;
    }

    @AssertTrue(message = "Les cles p256dh et auth sont requises pour un abonnement Web Push")
    public boolean isKeysConsistent() {
        return kindOrDefault() == PushKind.FCM || keys != null;
    }

    public record Keys(
            @NotBlank @Size(max = 200) String p256dh,
            @NotBlank @Size(max = 100) String auth
    ) {
    }
}
