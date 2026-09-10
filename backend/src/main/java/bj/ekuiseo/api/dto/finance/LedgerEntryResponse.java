package bj.ekuiseo.api.dto.finance;

import bj.ekuiseo.api.domain.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

/** Ecriture du registre financier (contrat A.4), GET /api/v1/admin/finance/ledger. */
public record LedgerEntryResponse(
        UUID id,
        Instant createdAt,
        String entryType,
        String account,
        String direction,
        long amountFcfa,
        UUID bookingId,
        UUID paymentId,
        UUID refundId,
        UUID payoutId,
        UUID userId,
        String userName,
        String provider,
        String providerReference,
        String description
) {
    public static LedgerEntryResponse from(LedgerEntry e, String userName) {
        return new LedgerEntryResponse(e.getId(), e.getCreatedAt(), e.getEntryType().name(), e.getAccount().name(),
                e.getDirection().name(), e.getAmountFcfa(), e.getBookingId(), e.getPaymentId(), e.getRefundId(),
                e.getPayoutId(), e.getUserId(), userName, e.getProvider(), e.getProviderReference(), e.getDescription());
    }
}
