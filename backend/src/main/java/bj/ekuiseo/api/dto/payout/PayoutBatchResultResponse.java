package bj.ekuiseo.api.dto.payout;

import java.util.List;

public record PayoutBatchResultResponse(
        int payoutsCreated,
        long totalAmountFcfa,
        List<PayoutResponse> payouts
) {
}
