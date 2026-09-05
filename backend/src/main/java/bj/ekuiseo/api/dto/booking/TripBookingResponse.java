package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * Reservation vue par le conducteur du trajet (GET /api/v1/trips/{id}/bookings) :
 * de quoi reconnaitre le passager au depart, savoir ce qu il doit encore regler a
 * bord, et signaler une absence. Le numero de telephone n est pas expose : la
 * messagerie de la reservation sert au contact.
 */
public record TripBookingResponse(
        UUID id,
        UUID passengerId,
        String firstName,
        String lastName,
        String photoUrl,
        java.math.BigDecimal ratingAvg,
        int seats,
        BookingStatus status,
        PaymentMethod paymentMethod,
        long balanceDueOnBoard,
        UUID pickupStopId,
        UUID dropoffStopId,
        Instant createdAt
) {
}
