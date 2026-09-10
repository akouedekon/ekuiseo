package bj.ekuiseo.api.dto.finance;

import bj.ekuiseo.api.domain.ReconciliationAnomaly;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Ecart de rapprochement (contrat A.7). */
public record ReconciliationAnomalyResponse(
        UUID id,
        UUID runId,
        String kind,
        UUID paymentId,
        UUID bookingId,
        String providerTxId,
        Map<String, Object> expected,
        Map<String, Object> observed,
        String status,
        String resolutionNote,
        UUID resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {
    public static ReconciliationAnomalyResponse from(ReconciliationAnomaly a) {
        return new ReconciliationAnomalyResponse(a.getId(), a.getRunId(), a.getKind().name(), a.getPaymentId(),
                a.getBookingId(), a.getProviderTxId(), a.getExpected(), a.getObserved(), a.getStatus().name(),
                a.getResolutionNote(), a.getResolvedBy(), a.getResolvedAt(), a.getCreatedAt());
    }
}
