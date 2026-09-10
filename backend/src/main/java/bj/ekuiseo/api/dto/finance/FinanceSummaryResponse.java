package bj.ekuiseo.api.dto.finance;

import java.util.List;

/**
 * Synthese financiere du back-office (contrat A.4), GET /api/v1/admin/finance/summary?days=N :
 * totaux du registre sur la periode, reversements encore dus (lots PENDING/PROCESSING), files
 * d anomalies et de remboursements ouvertes, et detail par mois civil du Benin.
 */
public record FinanceSummaryResponse(
        int periodDays,
        long passengerPaidFcfa,
        long providerFeesFcfa,
        long platformCommissionFcfa,
        long driverShareFcfa,
        long refundedFcfa,
        long paidOutFcfa,
        long pendingPayoutFcfa,
        long cashOnBoardFcfa,
        long openAnomalies,
        long openRefunds,
        List<Month> byMonth
) {
    public record Month(String month, long passengerPaidFcfa, long commissionFcfa, long refundedFcfa,
                        long paidOutFcfa, long cashOnBoardFcfa) {
    }
}
