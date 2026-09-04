package bj.ekuiseo.api.dto.payment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record InitiatePaymentRequest(
        @NotNull UUID bookingId
) {
}
