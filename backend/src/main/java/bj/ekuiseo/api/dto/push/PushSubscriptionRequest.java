package bj.ekuiseo.api.dto.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/me/push-subscriptions : forme standard {@code PushSubscription.toJSON()} du
 * navigateur ({@code endpoint}, {@code keys.p256dh}, {@code keys.auth}). {@code expirationTime}
 * est ignore.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2000) String endpoint,
        @NotNull @Valid Keys keys
) {
    public record Keys(
            @NotBlank @Size(max = 200) String p256dh,
            @NotBlank @Size(max = 100) String auth
    ) {
    }
}
