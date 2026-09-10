package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;

import java.time.Instant;
import java.util.UUID;

/** Remboursement d un paiement (contrat A.3), vu par le passager et l administration. */
public record RefundResponse(
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
        /** Vrai quand la decision vient du systeme (annulation, echeance), faux pour une demande d administrateur. */
        boolean automatic,
        Instant requestedAt,
        Instant completedAt
) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPayment().getId(), r.getBookingId(), r.getAmountFcfa(), r.getKind(),
                r.getReason(), r.getStatus(), r.getAttempts(), r.getLastError(), r.getProviderReference(),
                r.getRequestedBy() == null, r.getCreatedAt(), r.getCompletedAt());
    }
}
