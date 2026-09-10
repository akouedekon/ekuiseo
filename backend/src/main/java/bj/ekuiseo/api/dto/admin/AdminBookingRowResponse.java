package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

/** Ligne de la liste des reservations du back-office (contrat A.10), GET /api/v1/admin/bookings. */
public record AdminBookingRowResponse(
        UUID id,
        BookingStatus status,
        Instant createdAt,
        Instant departureAt,
        String route,
        String passengerName,
        String driverName,
        int seats,
        long amountFcfa,
        long depositFcfa,
        PaymentMethod paymentMethod,
        /** Etat de paiement consolide (memes valeurs que BookingPaymentStateResponse.paymentState). */
        String paymentState,
        String cashStatus
) {
}
