package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.admin.AdminOverviewResponse;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.ReconciliationAnomalyRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Files de travail du back-office (GET /api/v1/admin/overview, constat F313) : uniquement
 * des comptages et sommes SQL, jamais de collection chargee en memoire. Contrat A.7 :
 * anomalies de rapprochement et remboursements ouverts.
 */
@Service
public class AdminOverviewService {

    static final List<PaymentStatus> REFUNDS_TO_HANDLE = List.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_MANUAL);
    /** Au-dela de ce nombre de messages en 24 h, un compte merite un regard (spam, harcelement - constat F555). */
    static final int HEAVY_SENDER_THRESHOLD = 50;

    private final ReportRepository reportRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final PaymentRepository paymentRepository;
    private final MessageRepository messageRepository;
    private final ReconciliationAnomalyRepository anomalyRepository;
    private final RefundRepository refundRepository;

    public AdminOverviewService(ReportRepository reportRepository,
                                IdentityVerificationRepository identityVerificationRepository,
                                DriverPayoutRepository driverPayoutRepository, PaymentRepository paymentRepository,
                                MessageRepository messageRepository, ReconciliationAnomalyRepository anomalyRepository,
                                RefundRepository refundRepository) {
        this.reportRepository = reportRepository;
        this.identityVerificationRepository = identityVerificationRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.paymentRepository = paymentRepository;
        this.messageRepository = messageRepository;
        this.anomalyRepository = anomalyRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse compute() {
        return new AdminOverviewResponse(
                reportRepository.countByStatus(ReportStatus.OPEN),
                reportRepository.countByStatus(ReportStatus.IN_REVIEW),
                identityVerificationRepository.countByStatus(IdentityVerificationStatus.PENDING),
                identityVerificationRepository.findFirstByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus.PENDING)
                        .map(IdentityVerification::getSubmittedAt).orElse(null),
                driverPayoutRepository.countByStatus(PayoutStatus.PENDING),
                driverPayoutRepository.sumAmountByStatus(PayoutStatus.PENDING),
                paymentRepository.countByStatusIn(REFUNDS_TO_HANDLE),
                messageRepository.countHeavySenders(Instant.now().minus(24, ChronoUnit.HOURS), HEAVY_SENDER_THRESHOLD),
                anomalyRepository.countByStatus(AnomalyStatus.OPEN),
                refundRepository.countOpen());
    }
}
