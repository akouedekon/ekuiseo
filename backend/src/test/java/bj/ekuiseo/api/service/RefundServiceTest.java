package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.payment.AdminRefundResponse;
import bj.ekuiseo.api.dto.payment.RefundSummaryResponse;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.payment.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constats F004, F036, F105, F106 et contrat A.3 : decision dans la transaction, execution a part,
 * machine d etat REQUESTED -> PROCESSING -> SUCCEEDED | FAILED | MANUAL_REVIEW, reprise, marquage
 * admin, jamais deux remboursements vivants pour un paiement. Le depot des remboursements est
 * simule en memoire.
 */
class RefundServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentProvider gateway = mock(PaymentProvider.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PayoutService payoutService = mock(PayoutService.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentEventService paymentEventService = mock(PaymentEventService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final List<Runnable> executed = new ArrayList<>();
    private final Map<UUID, Refund> refunds = new ConcurrentHashMap<>();
    private RefundService service;
    private User passenger;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        // Gestionnaire de transaction factice : TransactionTemplate execute le code tel quel.
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new RefundService(paymentRepository, refundRepository, gateway, auditService, notificationService,
                payoutService, ledgerService, paymentEventService, txManager, executed::add, 3);
        passenger = User.builder().id(UUID.randomUUID()).phone("+2290197000321").firstName("Jean").lastName("K").build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(User.builder().id(UUID.randomUUID()).build()).build();
        booking = Booking.builder().id(UUID.randomUUID()).passenger(passenger).trip(trip).status(BookingStatus.CANCELLED_BY_PASSENGER)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).depositAmount(1000).amount(3500).serviceFee(280).build();
        payment = Payment.builder().id(UUID.randomUUID()).booking(booking).providerTxId("kk-tx-1").amount(1000)
                .status(PaymentStatus.SUCCEEDED).build();
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.SUCCEEDED))
                .thenAnswer(inv -> payment.getStatus() == PaymentStatus.SUCCEEDED ? Optional.of(payment) : Optional.empty());
        when(paymentRepository.findByBookingId(booking.getId())).thenReturn(List.of(payment));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            if (r.getCreatedAt() == null) r.setCreatedAt(Instant.now());
            refunds.put(r.getId(), r);
            return r;
        });
        when(refundRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(refunds.get(inv.getArgument(0))));
        when(refundRepository.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(refunds.get(inv.getArgument(0))));
        when(refundRepository.findLiveByPaymentId(any())).thenAnswer(inv -> refunds.values().stream()
                .filter(r -> r.getPayment().getId().equals(inv.getArgument(0)) && r.isAlive()).toList());
        when(refundRepository.findByBookingIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> refunds.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getBookingId())).toList());
    }

    private Refund only() {
        assertThat(refunds).hasSize(1);
        return refunds.values().iterator().next();
    }

    @Test
    void fullRefund_isRequested_thenExecutedOutsideTheTransaction_untilSucceeded() {
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.REQUESTED);
        Refund refund = only();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getKind()).isEqualTo(RefundKind.FULL);
        assertThat(refund.getAmountFcfa()).isEqualTo(1000);
        assertThat(refund.getBookingId()).isEqualTo(booking.getId());
        assertThat(refund.getRequestedBy()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundAmount()).isEqualTo(1000);
        verify(payoutService).excludeCancelledBooking(booking, "ANNULATION_PASSAGER");
        verify(gateway, never()).refundTransaction(anyString()); // rien ne part dans la transaction
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUND_PENDING), any());
        verify(paymentEventService).record(eq(payment), eq(PaymentEventService.REFUND_REQUESTED), eq(PaymentStatus.SUCCEEDED),
                eq(PaymentStatus.REFUND_PENDING), any(), any(), any());
        assertThat(executed).hasSize(1);

        when(gateway.refundTransaction("kk-tx-1")).thenReturn(new PaymentProvider.RefundResult(true, "ok", null));
        executed.get(0).run();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        verify(ledgerService).recordRefundSucceeded(refund);
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUNDED), any());
        verify(auditService).log(any(), eq("PAYMENT_REFUNDED"), eq("payment"), eq(payment.getId()), any());
        verify(paymentEventService).record(eq(payment), eq(PaymentEventService.REFUND_SUCCEEDED), eq(PaymentStatus.REFUND_PENDING),
                eq(PaymentStatus.REFUNDED), any(), any(), any());
    }

    /** Idempotence de process : un remboursement deja PROCESSING ou SUCCEEDED ne repart jamais. */
    @Test
    void process_isIdempotent_andNeverRunsTwice() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        Refund refund = only();
        when(gateway.refundTransaction("kk-tx-1")).thenReturn(new PaymentProvider.RefundResult(true, "ok", null));

        service.process(refund.getId());
        service.process(refund.getId());
        service.process(refund.getId());

        verify(gateway, times(1)).refundTransaction("kk-tx-1");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);

        // Un remboursement passe PROCESSING par un autre fil n est pas reexecute.
        Refund inFlight = Refund.builder().id(UUID.randomUUID()).payment(payment).bookingId(booking.getId()).amountFcfa(1000)
                .kind(RefundKind.FULL).reason("X").status(RefundStatus.PROCESSING).build();
        inFlight.setUpdatedAt(Instant.now());
        refunds.put(inFlight.getId(), inFlight);
        service.process(inFlight.getId());
        verify(gateway, times(1)).refundTransaction("kk-tx-1");
    }

    @Test
    void gatewayFailure_goesToFailed_thenRetried_thenManualReviewAfterMaxAttempts() {
        service.requestForBooking(booking, 1000, "ANNULATION_CONDUCTEUR");
        Refund refund = only();
        when(gateway.refundTransaction("kk-tx-1")).thenThrow(new RuntimeException("Fournisseur injoignable"));

        service.process(refund.getId());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getCompletedAt()).isNull(); // en reprise, pas definitif
        assertThat(refund.getAttempts()).isEqualTo(1);
        assertThat(refund.getLastError()).contains("injoignable");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundAttempts()).isEqualTo(1);

        service.process(refund.getId());
        service.process(refund.getId());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
        verify(auditService).log(any(), eq("REFUND_MANUAL_REQUIRED"), eq("payment"), eq(payment.getId()), any());

        // Plus rien ne part pour un remboursement en examen.
        service.process(refund.getId());
        verify(gateway, times(3)).refundTransaction("kk-tx-1");
    }

    @Test
    void retryPending_delegatesToTheRepository_andProcessesEachId() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        Refund refund = only();
        refund.setStatus(RefundStatus.FAILED);
        refund.setAttempts(1);
        when(refundRepository.findRetryable(any(), any(), eq(3))).thenReturn(List.of(refund.getId()));
        when(gateway.refundTransaction("kk-tx-1")).thenReturn(new PaymentProvider.RefundResult(true, "ok", null));

        assertThat(service.retryPending(Instant.now())).isEqualTo(1);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void partialRefund_goesStraightToManualReview() {
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 500, "ANNULATION_PASSAGER");

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.MANUAL_REQUIRED);
        Refund refund = only();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
        assertThat(refund.getKind()).isEqualTo(RefundKind.PARTIAL);
        assertThat(refund.getAmountFcfa()).isEqualTo(500);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
        assertThat(payment.getRefundAmount()).isEqualTo(500);
        assertThat(executed).isEmpty();
        verify(gateway, never()).refundTransaction(anyString());
    }

    /** Jamais deux remboursements vivants pour un paiement : la seconde demande retrouve la premiere. */
    @Test
    void secondRequestForTheSamePayment_isRefused() {
        assertThat(service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER").status())
                .isEqualTo(RefundService.RequestStatus.REQUESTED);
        assertThat(service.requestForBooking(booking, 1000, "CONDUCTEUR_ABSENT").status())
                .isEqualTo(RefundService.RequestStatus.ALREADY_REQUESTED);
        assertThat(service.requestForOrphanPayment(payment, passenger, RefundService.REASON_ORPHAN, 1000).status())
                .isEqualTo(RefundService.RequestStatus.ALREADY_REQUESTED);
        assertThat(refunds).hasSize(1);
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
        when(paymentRepository.findByBookingId(unpaid.getId())).thenReturn(List.of());
        assertThat(service.requestForBooking(unpaid, 1000, "X").status()).isEqualTo(RefundService.RequestStatus.NO_PAYMENT);
        verify(auditService).log(any(), eq("REFUND_NO_PAYMENT_FOUND"), eq("booking"), eq(unpaid.getId()), any());
    }

    @Test
    void orphanPayment_isRefundedInFull() {
        RefundService.RequestOutcome outcome = service.requestForOrphanPayment(payment, passenger, RefundService.REASON_ORPHAN, 1000);

        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.REQUESTED);
        assertThat(only().getReason()).isEqualTo("PAYMENT_ORPHAN");
        assertThat(payment.getRefundReason()).isEqualTo("PAYMENT_ORPHAN");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(auditService).log(any(), eq("PAYMENT_REFUND_REQUESTED"), eq("payment"), eq(payment.getId()), any());
    }

    @Test
    void paymentWithoutGatewayId_cannotBeRefundedAutomatically() {
        payment.setProviderTxId("ekuiseo-booking-123");
        RefundService.RequestOutcome outcome = service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        assertThat(outcome.status()).isEqualTo(RefundService.RequestStatus.MANUAL_REQUIRED);
        assertThat(only().getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_MANUAL);
    }

    @Test
    void markSucceeded_closesTheCase_withTheReference() {
        service.requestForBooking(booking, 500, "ANNULATION_PASSAGER");
        Refund refund = only();
        UUID admin = UUID.randomUUID();

        AdminRefundResponse res = service.markSucceeded(admin, refund.getId(), "REF-KK-42");

        assertThat(res.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(res.providerReference()).isEqualTo("REF-KK-42");
        assertThat(res.passengerId()).isEqualTo(passenger.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(ledgerService).recordRefundSucceeded(refund);
        verify(auditService).log(eq(admin), eq("PAYMENT_MARKED_REFUNDED"), eq("payment"), eq(payment.getId()), any());
        verify(notificationService).notify(eq(passenger), eq(NotificationType.PAYMENT_REFUNDED), any());
        assertThatThrownBy(() -> service.markSucceeded(admin, refund.getId(), null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void retry_fromManualReview_replaysTheGateway_andRefusesPartials() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        Refund refund = only();
        refund.setStatus(RefundStatus.MANUAL_REVIEW);
        refund.setAttempts(3);
        payment.setStatus(PaymentStatus.REFUND_MANUAL);
        when(gateway.refundTransaction("kk-tx-1")).thenReturn(new PaymentProvider.RefundResult(true, "ok", null));

        AdminRefundResponse res = service.retry(UUID.randomUUID(), refund.getId());

        assertThat(res.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(any(), eq("PAYMENT_REFUND_RETRIED"), eq("payment"), eq(payment.getId()), any());

        Refund partial = Refund.builder().id(UUID.randomUUID()).payment(payment).bookingId(booking.getId()).amountFcfa(500)
                .kind(RefundKind.PARTIAL).reason("X").status(RefundStatus.MANUAL_REVIEW).build();
        refunds.put(partial.getId(), partial);
        assertThatThrownBy(() -> service.retry(UUID.randomUUID(), partial.getId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void fail_isDefinitive_andGivesThePaymentBackItsSucceededStatus() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        Refund refund = only();
        refund.setStatus(RefundStatus.MANUAL_REVIEW);
        payment.setStatus(PaymentStatus.REFUND_MANUAL);

        AdminRefundResponse res = service.fail(UUID.randomUUID(), refund.getId(), "Passager rembourse en main propre");

        assertThat(res.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.isAlive()).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThatThrownBy(() -> service.fail(UUID.randomUUID(), refund.getId(), "")).isInstanceOf(BadRequestException.class);
        // Un remboursement abandonne n apparait plus dans le sort de l argent vu par le passager.
        assertThat(service.summaryForBooking(booking.getId())).isEmpty();
    }

    @Test
    void summaryForBooking_reflectsTheRefundStatus() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        Refund refund = only();
        assertThat(service.summaryForBooking(booking.getId())).map(RefundSummaryResponse::status).contains(RefundSummaryResponse.PENDING);
        refund.setStatus(RefundStatus.MANUAL_REVIEW);
        assertThat(service.summaryForBooking(booking.getId())).map(RefundSummaryResponse::status).contains(RefundSummaryResponse.MANUAL);
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setCompletedAt(Instant.now());
        assertThat(service.summaryForBooking(booking.getId())).map(RefundSummaryResponse::status).contains(RefundSummaryResponse.REFUNDED);
        assertThat(service.summaryForBooking(UUID.randomUUID())).isEmpty();
    }

    /** Routes historiques /admin/payments/{id}/... : elles agissent sur le remboursement vivant du paiement. */
    @Test
    void legacyPaymentRoutes_delegateToTheLiveRefund() {
        service.requestForBooking(booking, 1000, "ANNULATION_PASSAGER");
        var res = service.markRefunded(UUID.randomUUID(), payment.getId(), "tableau de bord");
        assertThat(res.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(res.refundedAt()).isNotNull();
        assertThatThrownBy(() -> service.markRefunded(UUID.randomUUID(), payment.getId(), null))
                .isInstanceOf(BadRequestException.class);
    }
}
