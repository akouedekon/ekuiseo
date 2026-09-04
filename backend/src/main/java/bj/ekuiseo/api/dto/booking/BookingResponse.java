package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID tripId,
        UUID passengerId,
        int seats,
        long amount,
        long serviceFee,
        BookingStatus status,
        PaymentMethod paymentMethod,
        Instant createdAt
) {
}
