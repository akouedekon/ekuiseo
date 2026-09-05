package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F004, F036, F105, F106 : decision dans la transaction, execution Kkiapay a part, reprise et file manuelle. */
class RefundServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final KkiapayGateway gateway = mock(KkiapayGateway.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PayoutService payoutService = mock(PayoutService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final List<Runnable> executed = new ArrayList<>();
    private RefundService service;
    private User passenger;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        // Gestionnaire de transaction factice : TransactionTemplate execute le code tel quel.
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new RefundService(paymentRepository, gateway, auditService, notificationService, payoutService,
                txManager, executed::add, 3);
        passenger = User.builder().id(UUID.randomUUID()).phone("+2290197000321").build();
        booking = Booking.builder().id(UUID.randomUUID()).passenger(passenger).status(BookingStatus.CANCELLED_BY_PASSENGER)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).depositAmount(1000).amount(3500).build();
        payment = Payment.builder().id(UUID.randomUUID()).booking(booking).providerTxId("kk-tx-1").amount(1000)
                .status(PaymentStatus.SUCCEEDED).build();
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fullRefund_isMarkedPending_thenExecutedOutsideTheTransaction() {
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.REQUESTED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundAmount()).isEqualTo(1000);
        verify(payoutService).excludeCancelledBooking(booking, "ANNULATION_PASSAGER");
        verify(gateway, never()).refundTransaction(anyString()); // rien ne part dans la transaction
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUND_PENDING), any());
        assertThat(executed).hasSize(1);

        when(gateway.refundTransaction("kk-tx-1")).thenReturn(new KkiapayGateway.RefundResult(true, "ok", null));
        executed.get(0).run();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUNDED), any());
        verify(auditService).log(any(), eq("PAYMENT_REFUNDED"), eq("payment"), eq(payment.getId()), any());
    }

    @Test
    void gatewayFailure_countsAttempts_thenFallsBackToManual() {
        service.requestForBooking(booking, 1000, "ANNULATION_CONDUCTEUR");
        when(gateway.refundTransaction("kk-tx-1")).thenThrow(new RuntimeException("Kkiapay injoignable"));

        service.process(payment.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundAttempts()).isEqualTo(1);
        assertThat(payment.getRefundLastError()).contains("injoignable");

        service.process(payment.getId());
        service.process(payment.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
        verify(auditService).log(any(), eq("REFUND_MANUAL_REQUIRED"), eq("payment"), eq(payment.getId()), any());

        // Plus rien ne part pour un paiement passe en manuel.
        service.process(payment.getId());
        verify(gateway, times(3)).refundTransaction("kk-tx-1");
    }

    @Test
    void partialRefund_goesStraightToManualQueue() {
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 500, "ANNULATION_PASSAGER");

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.MANUAL_REQUIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
        assertThat(payment.getRefundAmount()).isEqualTo(500);
        assertThat(executed).isEmpty();
        verify(gateway, never()).refundTransaction(anyString());
    }

    @Test
    void cashOrUnpaidBookings_requestNothing_butStillAdjustPayouts() {
        Booking cash = Booking.builder().id(UUID.randomUUID()).passenger(passenger).paymentMethod(PaymentMethod.CASH).build();
        assertThat(service.requestForBooking(cash, 0, "X").status()).isEqualTo(RefundService.RequestStatus.NOT_APPLICABLE);
        verify(payoutService).excludeCancelledBooking(cash, "X");

        Booking unpaid = Booking.builder().id(UUID.randomUUID()).passenger(passenger).paymentMethod(PaymentMethod.MOMO_DEPOSIT)
                .depositAmount(1000).build();
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(unpaid.getId(), PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        assertThat(service.requestForBooking(unpaid, 1000, "X").status()).isEqualTo(RefundService.RequestStatus.NO_PAYMENT);
        verify(auditService).log(any(), eq("REFUND_NO_PAYMENT_FOUND"), eq("booking"), eq(unpaid.getId()), any());
    }

    @Test
    void orphanPayment_isRefundedInFull() {
        RefundService.RequestOutcome outcome = service.requestForOrphanPayment(payment, passenger, RefundService.REASON_ORPHAN, 1000);

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.REQUESTED);
        assertThat(payment.getRefundReason()).isEqualTo("PAYMENT_ORPHAN");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(auditService).log(any(), eq("PAYMENT_REFUND_REQUESTED"), eq("payment"), eq(payment.getId()), any());
    }

    @Test
    void paymentWithoutGatewayId_cannotBeRefundedAutomatically() {
        payment.setProviderTxId("ekuiseo-booking-123");
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.MANUAL_REQUIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
    }

    @Test
    void markRefunded_closesTheCase() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        service.markRefunded(UUID.randomUUID(), payment.getId(), "rembourse via le tableau de bord");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(any(), eq("PAYMENT_MARKED_REFUNDED"), eq("payment"), eq(payment.getId()), any());
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUNDED), any());
    }

    @SuppressWarnings("unused")
    private static TransactionStatus status() {
        return new SimpleTransactionStatus();
    }
}
