package bj.ekuiseo.api.dto.subscription;

import bj.ekuiseo.api.domain.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        long priceFcfa,
        SubscriptionStatus status,
        boolean currentlyActive,
        Instant startedAt,
        Instant currentPeriodEnd
) {
}
