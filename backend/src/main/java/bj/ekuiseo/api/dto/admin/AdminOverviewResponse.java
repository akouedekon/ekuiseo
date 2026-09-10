package bj.ekuiseo.api.dto.admin;

import java.time.Instant;

/**
 * Vue d ensemble des files de travail du back-office, GET /api/v1/admin/overview
 * (constat F313) : ce qui attend une action humaine, en une requete par compteur.
 *
 * @param openReports signalements OPEN
 * @param inReviewReports signalements IN_REVIEW
 * @param pendingVerifications dossiers d identite PENDING
 * @param oldestPendingVerificationAt date de depot du plus ancien dossier PENDING (null si aucun)
 * @param pendingPayouts lots de reversement PENDING
 * @param pendingPayoutsAmountFcfa montant total du aux conducteurs dans ces lots
 * @param refundsToHandle remboursements REFUND_PENDING ou REFUND_MANUAL (paiements)
 * @param heavyMessageSenders comptes ayant envoye plus de 50 messages sur les dernieres 24 h (abus possible, constat F555)
 * @param openAnomalies ecarts de rapprochement OPEN (contrat A.7)
 * @param openRefunds remboursements vivants non aboutis (REQUESTED, PROCESSING, FAILED en reprise, MANUAL_REVIEW)
 */
public record AdminOverviewResponse(
        long openReports,
        long inReviewReports,
        long pendingVerifications,
        Instant oldestPendingVerificationAt,
        long pendingPayouts,
        long pendingPayoutsAmountFcfa,
        long refundsToHandle,
        long heavyMessageSenders,
        long openAnomalies,
        long openRefunds
) {
}
