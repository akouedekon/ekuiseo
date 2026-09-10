package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NoShowResolution;
import bj.ekuiseo.api.domain.enums.PassengerConfirmation;
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
        Instant createdAt,
        /** Constat du passager apres le depart (V21) : PENDING, TRIP_DONE ou DRIVER_NO_SHOW. */
        PassengerConfirmation passengerConfirmation,
        Instant passengerConfirmedAt,
        /** Dossier « conducteur absent » (V25) : echeance du remboursement automatique, contestation, issue. */
        Instant driverNoShowRefundDueAt,
        Instant driverNoShowContestedAt,
        NoShowResolution driverNoShowResolution,
        Instant driverNoShowResolvedAt
) {
}
