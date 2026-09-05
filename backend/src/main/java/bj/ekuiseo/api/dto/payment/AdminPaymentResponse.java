package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.enums.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/** Vue back-office d un paiement a suivre (GET /api/v1/admin/payments) : remboursements en attente, manuels, effectues. */
public record AdminPaymentResponse(
        UUID id,
        UUID bookingId,
        UUID subscriptionId,
        UUID passengerId,
        String passengerName,
        String passengerPhone,
        String providerTxId,
        long amount,
        PaymentStatus status,
        Long refundAmount,
        String refundReason,
        Instant refundRequestedAt,
        int refundAttempts,
        String refundLastError,
        Instant refundedAt,
        Instant createdAt
) {
}
