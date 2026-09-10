package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.LedgerEntry;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.ReconciliationAnomaly;
import bj.ekuiseo.api.domain.ReconciliationRun;
import bj.ekuiseo.api.domain.enums.AnomalyKind;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import bj.ekuiseo.api.domain.enums.LedgerAccount;
import bj.ekuiseo.api.domain.enums.LedgerDirection;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.ReconciliationStatus;
import bj.ekuiseo.api.dto.finance.ReconciliationRunResponse;
import bj.ekuiseo.api.dto.finance.ResolveAnomalyRequest;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.ReconciliationAnomalyRepository;
import bj.ekuiseo.api.repository.ReconciliationRunRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contrat A.7 avec un fournisseur simule : ecart de montant, transaction inconnue, doublon et inconnu a l import, registre desequilibre. */
class ReconciliationServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final ReconciliationRunRepository runRepository = mock(ReconciliationRunRepository.class);
    private final ReconciliationAnomalyRepository anomalyRepository = mock(ReconciliationAnomalyRepository.class);
    private final KkiapayGateway gateway = mock(KkiapayGateway.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final List<ReconciliationAnomaly> anomalies = new ArrayList<>();
    private ReconciliationService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(gateway.name()).thenReturn("KKIAPAY");
        service = new ReconciliationService(paymentRepository, ledgerEntryRepository, refundRepository, runRepository,
                anomalyRepository, gateway, auditService, txManager, 5);
        Booking booking = Booking.builder().id(UUID.randomUUID()).build();
        payment = Payment.builder().id(UUID.randomUUID()).booking(booking).provider(PaymentProvider.KKIAPAY)
                .providerTxId("kk-1").amount(1000).verifiedAmount(1000L).status(PaymentStatus.SUCCEEDED).build();
        payment.setUpdatedAt(Instant.now());
        when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(inv -> {
            ReconciliationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(runRepository.findById(any())).thenAnswer(inv -> Optional.of(ReconciliationRun.builder().id(inv.getArgument(0))
                .trigger(bj.ekuiseo.api.domain.enums.ReconciliationTrigger.MANUAL).build()));
        when(anomalyRepository.save(any(ReconciliationAnomaly.class))).thenAnswer(inv -> {
            ReconciliationAnomaly a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
                anomalies.add(a);
            }
            return a;
        });
        when(anomalyRepository.existsByKindAndPaymentIdAndStatus(any(), any(), any())).thenAnswer(inv -> anomalies.stream()
                .anyMatch(a -> a.getKind() == inv.getArgument(0) && inv.getArgument(1).equals(a.getPaymentId()) && a.getStatus() == AnomalyStatus.OPEN));
        when(anomalyRepository.existsByKindAndProviderTxIdAndStatus(any(), any(), any())).thenAnswer(inv -> anomalies.stream()
                .anyMatch(a -> a.getKind() == inv.getArgument(0) && inv.getArgument(1).equals(a.getProviderTxId()) && a.getStatus() == AnomalyStatus.OPEN));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findForReconciliation(eq(PaymentProvider.KKIAPAY), any(), any())).thenReturn(List.of(payment));
        when(ledgerEntryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(balanced(payment, 1000, 320));
    }

    private static List<LedgerEntry> balanced(Payment p, long paid, long commission) {
        return List.of(
                LedgerEntry.builder().entryType(LedgerEntryType.PASSENGER_PAYMENT).account(LedgerAccount.PASSENGER)
                        .direction(LedgerDirection.DEBIT).amountFcfa(paid).paymentId(p.getId()).build(),
                LedgerEntry.builder().entryType(LedgerEntryType.PLATFORM_COMMISSION).account(LedgerAccount.PLATFORM)
                        .direction(LedgerDirection.CREDIT).amountFcfa(commission).paymentId(p.getId()).build(),
                LedgerEntry.builder().entryType(LedgerEntryType.DRIVER_SHARE).account(LedgerAccount.DRIVER)
                        .direction(LedgerDirection.CREDIT).amountFcfa(paid - commission).paymentId(p.getId()).build());
    }

    @Test
    void run_findsAnAmountMismatch_andDoesNotDuplicateOpenAnomalies() {
        when(gateway.verifyTransaction("kk-1")).thenReturn(new KkiapayGateway.VerificationResult(true, "kk-1", 900, 19, "SUCCESS", null, null));

        ReconciliationRunResponse res = service.run(UUID.randomUUID(), 7);

        assertThat(res.status()).isEqualTo(ReconciliationStatus.DONE.name());
        assertThat(res.checked()).isEqualTo(1);
        assertThat(res.anomaliesFound()).isEqualTo(1);
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getKind()).isEqualTo(AnomalyKind.AMOUNT_MISMATCH);
        assertThat(anomalies.get(0).getExpected()).containsEntry("amountFcfa", 1000L);
        assertThat(anomalies.get(0).getObserved()).containsEntry("amountFcfa", 900L);
        assertThat(anomalies.get(0).getBookingId()).isEqualTo(payment.getBooking().getId());

        // Un second rapprochement ne recree pas l ecart encore ouvert.
        assertThat(service.run(null, 7).anomaliesFound()).isZero();
        assertThat(anomalies).hasSize(1);
    }

    @Test
    void run_flagsATransactionUnknownAtTheProvider() {
        when(gateway.verifyTransaction("kk-1")).thenReturn(new KkiapayGateway.VerificationResult(false, "kk-1", 0, 0, "HTTP_404", "NOT_FOUND", "inconnue"));

        service.run(UUID.randomUUID(), 7);

        assertThat(anomalies).extracting(ReconciliationAnomaly::getKind).containsExactly(AnomalyKind.MISSING_AT_PROVIDER);
    }

    /** Une reponse transitoire (HTTP 5xx, PENDING) n est pas un ecart ; une erreur reseau est comptee, jamais bloquante. */
    @Test
    void run_ignoresTransientAnswers_andSurvivesGatewayErrors() {
        when(gateway.verifyTransaction("kk-1")).thenReturn(new KkiapayGateway.VerificationResult(false, "kk-1", 0, 0, "HTTP_503", "HTTP_ERROR", "x"));
        assertThat(service.run(UUID.randomUUID(), 7).anomaliesFound()).isZero();

        when(gateway.verifyTransaction("kk-1")).thenThrow(new RuntimeException("reseau"));
        ReconciliationRunResponse res = service.run(UUID.randomUUID(), 7);
        assertThat(res.checked()).isZero();
        assertThat(res.status()).isEqualTo(ReconciliationStatus.FAILED.name());
        assertThat(res.notes()).contains("1 erreur(s)");
        assertThat(anomalies).isEmpty();
    }

    @Test
    void run_flagsALedgerImbalance() {
        when(gateway.verifyTransaction("kk-1")).thenReturn(new KkiapayGateway.VerificationResult(true, "kk-1", 1000, 19, "SUCCESS", null, null));
        when(ledgerEntryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId())).thenReturn(balanced(payment, 1000, 400).subList(0, 2));

        service.run(UUID.randomUUID(), 7);

        assertThat(anomalies).extracting(ReconciliationAnomaly::getKind).containsExactly(AnomalyKind.LEDGER_IMBALANCE);
        assertThat(anomalies.get(0).getObserved()).containsEntry("driverShareFcfa", 0L);
    }

    @Test
    void importCsv_detectsDuplicates_unknownTransactions_andAmountMismatches() {
        when(paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, "kk-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, "kk-9")).thenReturn(Optional.empty());
        String csv = "﻿Référence;Montant;Statut;Client\n"
                + "kk-1;\"1 000\";Réussi;Jean\n"
                + "kk-9;500,00;SUCCESS;Fatou\n"
                + "kk-1;1 000 FCFA;Réussi;Jean\n";

        ReconciliationRunResponse res = service.importCsv(UUID.randomUUID(),
                new MockMultipartFile("file", "export.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(res.trigger()).isEqualTo("IMPORT");
        assertThat(res.checked()).isEqualTo(3);
        assertThat(anomalies).extracting(ReconciliationAnomaly::getKind)
                .containsExactlyInAnyOrder(AnomalyKind.UNKNOWN_AT_PROVIDER, AnomalyKind.DUPLICATE_PROVIDER_TX);
        assertThat(res.anomaliesFound()).isEqualTo(2);

        // Montant different sur une transaction connue.
        anomalies.clear();
        String mismatch = "transactionId,amount,status\nkk-1,\"1,500.00\",SUCCESS\n";
        service.importCsv(UUID.randomUUID(), new MockMultipartFile("file", "x.csv", "text/csv", mismatch.getBytes(StandardCharsets.UTF_8)));
        assertThat(anomalies).extracting(ReconciliationAnomaly::getKind).containsExactly(AnomalyKind.AMOUNT_MISMATCH);

        assertThatThrownBy(() -> service.importCsv(UUID.randomUUID(),
                new MockMultipartFile("file", "x.csv", "text/csv", "client;montant\nJean;10\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void parseAmount_toleratesFrenchFormats() {
        assertThat(ReconciliationService.parseAmount("1 000")).isEqualTo(1000);
        assertThat(ReconciliationService.parseAmount("1 000")).isEqualTo(1000);
        assertThat(ReconciliationService.parseAmount("1 000,00")).isEqualTo(1000);
        assertThat(ReconciliationService.parseAmount("1,000.50")).isEqualTo(1000);
        assertThat(ReconciliationService.parseAmount("2 500 FCFA")).isEqualTo(2500);
        assertThat(ReconciliationService.parseAmount("")).isNull();
        assertThat(ReconciliationService.parseAmount("abc")).isNull();
        assertThat(ReconciliationService.isSuccessWord("Réussi")).isTrue();
        assertThat(ReconciliationService.isSuccessWord("FAILED")).isFalse();
    }

    @Test
    void resolve_requiresAnOpenAnomaly_andAudits() {
        ReconciliationAnomaly anomaly = ReconciliationAnomaly.builder().id(UUID.randomUUID()).runId(UUID.randomUUID())
                .kind(AnomalyKind.AMOUNT_MISMATCH).paymentId(payment.getId()).build();
        when(anomalyRepository.findById(anomaly.getId())).thenReturn(Optional.of(anomaly));
        UUID admin = UUID.randomUUID();

        var res = service.resolve(admin, anomaly.getId(), new ResolveAnomalyRequest(AnomalyStatus.IGNORED, "Frais operateur"));

        assertThat(res.status()).isEqualTo("IGNORED");
        assertThat(res.resolvedBy()).isEqualTo(admin);
        assertThatThrownBy(() -> service.resolve(admin, anomaly.getId(), new ResolveAnomalyRequest(AnomalyStatus.RESOLVED, "x")))
                .isInstanceOf(bj.ekuiseo.api.common.exception.ConflictException.class);
        assertThatThrownBy(() -> service.resolve(admin, UUID.randomUUID(), new ResolveAnomalyRequest(AnomalyStatus.OPEN, "x")))
                .isInstanceOf(BadRequestException.class);
    }
}
