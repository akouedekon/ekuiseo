package bj.ekuiseo.api.dto.payout;

import java.util.List;
import java.util.UUID;

/**
 * Resultat d un lot de reversement (POST /api/v1/admin/payouts/run) : lots crees, et
 * conducteurs eligibles mais exclus (aucun compte mobile money verifie par defaut).
 */
public record PayoutBatchResultResponse(
        int payoutsCreated,
        long totalAmountFcfa,
        List<PayoutResponse> payouts,
        List<SkippedDriver> skipped
) {
    public record SkippedDriver(UUID driverId, String driverName, long amountFcfa, String reason) {
    }
}
