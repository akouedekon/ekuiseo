package bj.ekuiseo.api.dto.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DELETE /api/v1/me/push-subscriptions : l endpoint a retirer (celui du navigateur appelant). */
public record PushUnsubscribeRequest(
        @NotBlank @Size(max = 2000) String endpoint
) {
}
