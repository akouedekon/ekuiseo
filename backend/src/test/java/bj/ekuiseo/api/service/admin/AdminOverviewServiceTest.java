package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.admin.AdminOverviewResponse;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Constat F313 : les files de travail du back-office, en comptages SQL. */
class AdminOverviewServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final IdentityVerificationRepository verificationRepository = mock(IdentityVerificationRepository.class);
    private final DriverPayoutRepository payoutRepository = mock(DriverPayoutRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final bj.ekuiseo.api.repository.MessageRepository messageRepository = mock(bj.ekuiseo.api.repository.MessageRepository.class);
    private final AdminOverviewService service = new AdminOverviewService(reportRepository, verificationRepository,
            payoutRepository, paymentRepository, messageRepository);

    @Test
    void compute_aggregatesEveryQueue() {
        Instant oldest = Instant.parse("2026-09-01T10:00:00Z");
        when(reportRepository.countByStatus(ReportStatus.OPEN)).thenReturn(3L);
        when(reportRepository.countByStatus(ReportStatus.IN_REVIEW)).thenReturn(1L);
        when(verificationRepository.countByStatus(IdentityVerificationStatus.PENDING)).thenReturn(2L);
        when(verificationRepository.findFirstByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus.PENDING))
                .thenReturn(Optional.of(IdentityVerification.builder().submittedAt(oldest).build()));
        when(payoutRepository.countByStatus(PayoutStatus.PENDING)).thenReturn(4L);
        when(payoutRepository.sumAmountByStatus(PayoutStatus.PENDING)).thenReturn(18_400L);
        when(paymentRepository.countByStatusIn(List.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_MANUAL))).thenReturn(5L);
        when(messageRepository.countHeavySenders(org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq(AdminOverviewService.HEAVY_SENDER_THRESHOLD))).thenReturn(2L);

        AdminOverviewResponse res = service.compute();

        // Constat F555 : comptes ayant envoye plus de 50 messages en 24 h.
        assertThat(res.heavyMessageSenders()).isEqualTo(2);

        assertThat(res.openReports()).isEqualTo(3);
        assertThat(res.inReviewReports()).isEqualTo(1);
        assertThat(res.pendingVerifications()).isEqualTo(2);
        assertThat(res.oldestPendingVerificationAt()).isEqualTo(oldest);
        assertThat(res.pendingPayouts()).isEqualTo(4);
        assertThat(res.pendingPayoutsAmountFcfa()).isEqualTo(18_400L);
        assertThat(res.refundsToHandle()).isEqualTo(5);
    }

    @Test
    void compute_withEmptyQueues_hasNoOldestDate() {
        when(verificationRepository.findFirstByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThat(service.compute().oldestPendingVerificationAt()).isNull();
    }
}
