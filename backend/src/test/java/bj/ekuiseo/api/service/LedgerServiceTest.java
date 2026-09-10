package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.LedgerEntry;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.LedgerAccount;
import bj.ekuiseo.api.domain.enums.LedgerDirection;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.finance.LedgerAdjustmentRequest;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.ReconciliationAnomalyRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contrat A.4 : equilibre des ecritures d un paiement, contre-passation au prorata, correction decrite. */
class LedgerServiceTest {

    private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final LedgerService service = new LedgerService(repository, mock(DriverPayoutRepository.class),
            mock(ReconciliationAnomalyRepository.class), mock(RefundRepository.class), mock(UserRepository.class), auditService);
    private final List<LedgerEntry> written = new ArrayList<>();
    private Payment payment;
    private Booking booking;

    @BeforeEach
    void setUp() {
        User passenger = User.builder().id(UUID.randomUUID()).build();
        User driver = User.builder().id(UUID.randomUUID()).build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).build();
        booking = Booking.builder().id(UUID.randomUUID()).passenger(passenger).trip(trip).amount(7500).serviceFee(600)
                .depositAmount(1000).balanceDueOnBoard(6500).paymentMethod(PaymentMethod.MOMO_DEPOSIT).build();
        payment = Payment.builder().id(UUID.randomUUID()).booking(booking).providerTxId("kk-1").amount(1000)
                .status(PaymentStatus.SUCCEEDED).build();
        when(repository.save(any(LedgerEntry.class))).thenAnswer(inv -> {
            LedgerEntry e = inv.getArgument(0);
            written.add(e);
            return e;
        });
        when(repository.findByPaymentIdAndEntryType(any(), any())).thenAnswer(inv -> written.stream()
                .filter(e -> inv.getArgument(0).equals(e.getPaymentId()) && e.getEntryType() == inv.getArgument(1)).toList());
    }

    private long sum(LedgerEntryType type) {
        return written.stream().filter(e -> e.getEntryType() == type).mapToLong(LedgerEntry::getAmountFcfa).sum();
    }

    /** passager = commission + part conducteur ; les frais du fournisseur sont portes a part (cout de la plateforme). */
    @Test
    void paymentSucceeded_writesBalancedEntries_withProviderFee() {
        List<LedgerEntry> entries = service.recordPaymentSucceeded(payment, 1000, 19);

        assertThat(entries).hasSize(4);
        assertThat(sum(LedgerEntryType.PASSENGER_PAYMENT)).isEqualTo(1000);
        assertThat(sum(LedgerEntryType.PLATFORM_COMMISSION)).isEqualTo(600);
        assertThat(sum(LedgerEntryType.DRIVER_SHARE)).isEqualTo(400);
        assertThat(sum(LedgerEntryType.PROVIDER_FEE)).isEqualTo(19);
        assertThat(sum(LedgerEntryType.PASSENGER_PAYMENT))
                .isEqualTo(sum(LedgerEntryType.PLATFORM_COMMISSION) + sum(LedgerEntryType.DRIVER_SHARE));
        LedgerEntry paid = written.get(0);
        assertThat(paid.getAccount()).isEqualTo(LedgerAccount.PASSENGER);
        assertThat(paid.getDirection()).isEqualTo(LedgerDirection.DEBIT);
        assertThat(paid.getBookingId()).isEqualTo(booking.getId());
        assertThat(paid.getPaymentId()).isEqualTo(payment.getId());
        assertThat(paid.getUserId()).isEqualTo(booking.getPassenger().getId());
        assertThat(paid.getProvider()).isEqualTo("KKIAPAY");
        assertThat(paid.getProviderReference()).isEqualTo("kk-1");
        assertThat(written.stream().filter(e -> e.getEntryType() == LedgerEntryType.DRIVER_SHARE).findFirst().orElseThrow().getUserId())
                .isEqualTo(booking.getTrip().getDriver().getId());

        // Idempotent : le webhook et le widget peuvent se croiser.
        assertThat(service.recordPaymentSucceeded(payment, 1000, 19)).isEmpty();
        assertThat(written).hasSize(4);
    }

    /** Commission plafonnee au montant verifie, part conducteur jamais negative. */
    @Test
    void paymentSucceeded_capsTheCommissionToTheVerifiedAmount() {
        service.recordPaymentSucceeded(payment, 500, 0);

        assertThat(sum(LedgerEntryType.PLATFORM_COMMISSION)).isEqualTo(500);
        assertThat(sum(LedgerEntryType.DRIVER_SHARE)).isZero();
        assertThat(written).extracting(LedgerEntry::getEntryType).doesNotContain(LedgerEntryType.PROVIDER_FEE, LedgerEntryType.DRIVER_SHARE);
    }

    /** Remboursement integral : tout est contre-passe ; partiel : au prorata, la commission absorbe l arrondi. */
    @Test
    void refundSucceeded_reversesDriverShareAndCommission_proRata() {
        service.recordPaymentSucceeded(payment, 1000, 0);
        Refund full = Refund.builder().id(UUID.randomUUID()).payment(payment).bookingId(booking.getId()).amountFcfa(1000)
                .kind(RefundKind.FULL).reason("ANNULATION_CONDUCTEUR").status(RefundStatus.SUCCEEDED).build();

        service.recordRefundSucceeded(full);

        assertThat(sum(LedgerEntryType.REFUND)).isEqualTo(1000);
        assertThat(sum(LedgerEntryType.DRIVER_SHARE_REVERSAL)).isEqualTo(400);
        assertThat(sum(LedgerEntryType.COMMISSION_REVERSAL)).isEqualTo(600);
        assertThat(sum(LedgerEntryType.REFUND))
                .isEqualTo(sum(LedgerEntryType.DRIVER_SHARE_REVERSAL) + sum(LedgerEntryType.COMMISSION_REVERSAL));

        // Idempotent par remboursement.
        when(repository.existsByRefundIdAndEntryType(full.getId(), LedgerEntryType.REFUND)).thenReturn(true);
        assertThat(service.recordRefundSucceeded(full)).isEmpty();

        // Partiel (50 % retenus) : 500 rembourses -> 200 sur la part conducteur (400 x 500 / 1000), 300 sur la commission.
        written.clear();
        service.recordPaymentSucceeded(payment, 1000, 0);
        Refund partial = Refund.builder().id(UUID.randomUUID()).payment(payment).bookingId(booking.getId()).amountFcfa(500)
                .kind(RefundKind.PARTIAL).reason("ANNULATION_PASSAGER").status(RefundStatus.SUCCEEDED).build();
        service.recordRefundSucceeded(partial);
        assertThat(sum(LedgerEntryType.REFUND)).isEqualTo(500);
        assertThat(sum(LedgerEntryType.DRIVER_SHARE_REVERSAL)).isEqualTo(200);
        assertThat(sum(LedgerEntryType.COMMISSION_REVERSAL)).isEqualTo(300);
    }

    /** Paiement anterieur au registre (V26) : la repartition est reconstituee depuis la reservation. */
    @Test
    void refundOfALegacyPayment_reconstructsTheSplitFromTheBooking() {
        payment.setVerifiedAmount(1000L);
        Refund refund = Refund.builder().id(UUID.randomUUID()).payment(payment).bookingId(booking.getId()).amountFcfa(1000)
                .kind(RefundKind.FULL).reason("X").status(RefundStatus.SUCCEEDED).build();

        service.recordRefundSucceeded(refund);

        assertThat(sum(LedgerEntryType.REFUND)).isEqualTo(1000);
        assertThat(sum(LedgerEntryType.DRIVER_SHARE_REVERSAL)).isEqualTo(400);
        assertThat(sum(LedgerEntryType.COMMISSION_REVERSAL)).isEqualTo(600);
    }

    @Test
    void adjustment_requiresADescription_andIsAudited() {
        UUID admin = UUID.randomUUID();
        assertThatThrownBy(() -> service.adjust(admin, new LedgerAdjustmentRequest(null, null, LedgerAccount.PLATFORM,
                LedgerDirection.DEBIT, 500, "   ")))
                .isInstanceOf(BadRequestException.class);
        assertThat(written).isEmpty();

        var res = service.adjust(admin, new LedgerAdjustmentRequest(booking.getId(), null, LedgerAccount.PLATFORM,
                LedgerDirection.DEBIT, 500, "Geste commercial apres litige"));

        assertThat(res.entryType()).isEqualTo("ADJUSTMENT");
        assertThat(res.description()).isEqualTo("Geste commercial apres litige");
        assertThat(written.get(0).getCreatedBy()).isEqualTo(admin);
        verify(auditService).log(eq(admin), eq("LEDGER_ADJUSTMENT"), eq("ledger_entry"), any(), any());
    }

    @Test
    void payoutAndCash_areWrittenOnce() {
        DriverPayout payout = DriverPayout.builder().id(UUID.randomUUID()).driver(booking.getTrip().getDriver()).amount(2600)
                .status(PayoutStatus.SETTLED).destinationProvider(MobileMoneyOperator.MOOV_MONEY).externalReference("MM-1").build();
        service.recordPayoutSettled(payout, 2600);
        assertThat(sum(LedgerEntryType.PAYOUT)).isEqualTo(2600);
        assertThat(written.get(0).getProvider()).isEqualTo("MOOV_MONEY");
        assertThat(written.get(0).getProviderReference()).isEqualTo("MM-1");
        when(repository.existsByPayoutIdAndEntryType(payout.getId(), LedgerEntryType.PAYOUT)).thenReturn(true);
        service.recordPayoutSettled(payout, 2600);
        assertThat(written).hasSize(1);

        service.recordCashSettled(booking, 6500);
        assertThat(sum(LedgerEntryType.CASH_ON_BOARD)).isEqualTo(6500);
        assertThat(written.get(1).getProvider()).isEqualTo("CASH");
        assertThat(written.get(1).getAccount()).isEqualTo(LedgerAccount.DRIVER);
        service.recordCashSettled(booking, 0);
        assertThat(written).hasSize(2);
        verify(repository, never()).delete(any(LedgerEntry.class));
    }

    @Test
    void csvCell_escapesSeparatorsAndQuotes() {
        assertThat(LedgerService.csvCell(null)).isEmpty();
        assertThat(LedgerService.csvCell(1000L)).isEqualTo("1000");
        assertThat(LedgerService.csvCell("a;b")).isEqualTo("\"a;b\"");
        assertThat(LedgerService.csvCell("dit \"oui\"")).isEqualTo("\"dit \"\"oui\"\"\"");
    }
}
