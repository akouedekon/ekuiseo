package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;

import java.time.Instant;
import java.util.UUID;

/** {@link RefundResponse} enrichi du passager, pour le back-office (GET /api/v1/admin/refunds). */
public record AdminRefundResponse(
        UUID id,
        UUID paymentId,
        UUID bookingId,
        long amountFcfa,
        RefundKind kind,
        String reason,
        RefundStatus status,
        int attempts,
        String lastError,
        String providerReference,
        boolean automatic,
        Instant requestedAt,
        Instant completedAt,
        UUID passengerId,
        String passengerName,
        String passengerPhone
) {
    public static AdminRefundResponse from(Refund r) {
        Booking booking = r.getPayment().getBooking();
        User passenger = booking != null ? booking.getPassenger()
                : r.getPayment().getSubscription() != null ? r.getPayment().getSubscription().getDriver() : null;
        return new AdminRefundResponse(r.getId(), r.getPayment().getId(), r.getBookingId(), r.getAmountFcfa(), r.getKind(),
                r.getReason(), r.getStatus(), r.getAttempts(), r.getLastError(), r.getProviderReference(),
                r.getRequestedBy() == null, r.getCreatedAt(), r.getCompletedAt(),
                passenger != null ? passenger.getId() : null,
                passenger != null ? (passenger.getFirstName() + " " + passenger.getLastName()).trim() : null,
                passenger != null ? passenger.getPhone() : null);
    }
}
