package bj.ekuiseo.api.dto.payout;

import bj.ekuiseo.api.domain.enums.PayoutStatus;

import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        UUID id,
        UUID driverId,
        long amount,
        PayoutStatus status,
        String destinationMsisdn,
        Instant periodStart,
        Instant periodEnd,
        Instant requestedAt,
        Instant settledAt
) {
}
