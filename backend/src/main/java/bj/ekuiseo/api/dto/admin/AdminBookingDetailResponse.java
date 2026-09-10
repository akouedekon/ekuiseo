package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.booking.CashSettlementResponse;
import bj.ekuiseo.api.dto.finance.LedgerEntryResponse;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.payment.AdminRefundResponse;
import bj.ekuiseo.api.dto.payment.PaymentEventResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fiche d une reservation pour le back-office (contrat A.10), GET /api/v1/admin/bookings/{id} : tout ce qui touche a son argent. */
public record AdminBookingDetailResponse(
        Booking booking,
        List<AdminPaymentResponse> payments,
        List<AdminRefundResponse> refunds,
        List<PaymentEventResponse> events,
        List<LedgerEntryResponse> ledger,
        List<AuditEntry> audit
) {
    /** {@link AdminBookingRowResponse} enrichi des montants figes, des identifiants et des constats. */
    public record Booking(
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
            String paymentState,
            String cashStatus,
            long serviceFeeFcfa,
            long balanceDueOnBoardFcfa,
            String passengerConfirmation,
            String driverNoShowResolution,
            CashSettlementResponse cash,
            UUID passengerId,
            String passengerPhone,
            UUID driverId,
            UUID tripId,
            String tripStatus
    ) {
    }

    public record AuditEntry(String action, UUID actorId, Instant createdAt, Map<String, Object> details) {
    }
}
