package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentChannel;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.dto.payment.PaymentClientStatus;
import bj.ekuiseo.api.dto.payment.PaymentStatusResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmation d'un paiement depuis le widget Kkiapay (PaymentService#confirmFromWidget)
 * et traitement du webhook : dans les deux cas, rien n'est cru sur parole - statut ET
 * montant sont reverifies aupres de Kkiapay avant de confirmer la reservation. Phase 3 :
 * reutilisation du paiement INITIATED (F019/F149), verrou (F150), montant verifie et
 * surpaiement (F151), operateur reel (F140), webhook acquitte sans cible (F012).
 */
class PaymentServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final DriverSubscriptionRepository subscriptionRepository = mock(DriverSubscriptionRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final KkiapayGateway gateway = mock(KkiapayGateway.class);
    private final RefundService refundService = mock(RefundService.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentEventService paymentEventService = mock(PaymentEventService.class);
    private final PaymentWebhookService paymentWebhookService = mock(PaymentWebhookService.class);
    private final org.springframework.transaction.PlatformTransactionManager txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);

    private PaymentService service;
    private User passenger;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any(org.springframework.transaction.TransactionDefinition.class))).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new PaymentService(paymentRepository, bookingRepository, subscriptionRepository,
                notificationService, auditService, gateway, refundService, new KkiapayWebhookParser(new ObjectMapper()), new DriverApprovalPolicy(24),
                ledgerService, paymentEventService, paymentWebhookService, txManager, "pk_test", "secret", true);
        passenger = User.builder().id(UUID.randomUUID()).build();
        User driver = User.builder().id(UUID.randomUUID()).build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).build();
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .passenger(passenger)
                .trip(trip)
                .seats(1)
                .amount(4000)
                .serviceFee(320)
                .depositAmount(1000)
                .balanceDueOnBoard(3000)
                .status(BookingStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT)
                .build();
        payment = Payment.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .provider(PaymentProvider.KKIAPAY)
                .providerTxId("ekuiseo-booking-" + UUID.randomUUID())
                .amount(1000)
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findByProviderAndProviderTxId(eq(PaymentProvider.KKIAPAY), any()))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static KkiapayGateway.VerificationResult verified(boolean success, long amount, String raw) {
        return new KkiapayGateway.VerificationResult(success, "kk_123", amount, 19, raw, null, null);
    }

    private KkiapayWebhookPayload webhook(Object stateData) {
        return new KkiapayWebhookPayload("transaction.success", "kk_123", true, "22997000000",
                "Ekuiseo", "MOBILE_MONEY", 1000L, 19L, null, null, stateData);
    }

    @Test
    void confirmFromWidget_verifiedAndSufficient_confirmsBooking_underLock() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), " kk_123 ");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.SUCCEEDED);
        assertThat(res.transactionRef()).isEqualTo("kk_123");
        assertThat(res.bookingId()).isEqualTo(booking.getId());
        assertThat(res.subscriptionId()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(payment.getFee()).isEqualTo(19);
        assertThat(payment.getVerifiedAmount()).isEqualTo(1000);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(booking);
        // Constat F150 : le paiement est charge sous verrou pessimiste.
        verify(paymentRepository).findByIdForUpdate(payment.getId());
        verify(auditService, never()).log(any(), eq("PAYMENT_OVERPAID"), any(), any(), any());
    }

    @Test
    void confirmFromWidget_amountTooLow_doesNotConfirm() {
        // Le widget a ete ouvert avec 5 F au lieu de 1 000 F : Kkiapay dit SUCCESS, nous non.
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 5, "SUCCESS"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getVerifiedAmount()).isEqualTo(5);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(bookingRepository, never()).save(any());
    }

    /** Constat F151 : un surpaiement est accepte et journalise (PAYMENT_OVERPAID), jamais rembourse d office. */
    @Test
    void confirmFromWidget_overpayment_confirms_andAudits() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1500, "SUCCESS"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.SUCCEEDED);
        assertThat(payment.getVerifiedAmount()).isEqualTo(1500);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(null), eq("PAYMENT_OVERPAID"), eq("payment"), eq(payment.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("expectedAmountFcfa", 1000L)
                .containsEntry("verifiedAmountFcfa", 1500L)
                .containsEntry("excessFcfa", 500L);
        verify(refundService, never()).requestForOrphanPayment(any(), any(), any(), any(Long.class));
    }

    /** Constat F140 : l operateur reel vient de la verification, pas de la declaration du widget. */
    @Test
    void confirmFromWidget_recordsTheRealOperator() {
        payment.setChannel(PaymentChannel.MOOV); // declare par le passager a l initiation
        when(gateway.verifyTransaction("kk_123")).thenReturn(new KkiapayGateway.VerificationResult(
                true, "kk_123", 1000, 19, "SUCCESS", null, null, "MTN"));

        service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(payment.getChannel()).isEqualTo(PaymentChannel.MTN);
        assertThat(payment.getRawPayload()).containsEntry("operator", "MTN");
    }

    @Test
    void confirmFromWidget_pendingAtKkiapay_keepsWaitingForWebhook() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(false, 0, "PENDING"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.PROCESSING);
        assertThat(res.instruction()).isNotBlank();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmFromWidget_finalFailure_marksFailed() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(false, 1000, "FAILED"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.FAILED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmFromWidget_otherUser_isForbidden() {
        assertThatThrownBy(() -> service.confirmFromWidget(payment.getId(), UUID.randomUUID(), "kk_123"))
                .isInstanceOf(ForbiddenException.class);
        verify(gateway, never()).verifyTransaction(any());
    }

    @Test
    void confirmFromWidget_alreadySucceeded_isIdempotent() {
        payment.setStatus(PaymentStatus.SUCCEEDED);
        booking.setStatus(BookingStatus.CONFIRMED);

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo(PaymentClientStatus.SUCCEEDED);
        verify(gateway, never()).verifyTransaction(any());
    }

    @Test
    void getStatus_exposesTheClientVocabulary_andRealUpdatedAt() {
        Instant updated = Instant.parse("2026-09-07T10:00:00Z");
        payment.setStatus(PaymentStatus.REFUND_MANUAL);
        payment.setUpdatedAt(updated);

        PaymentStatusResponse res = service.getStatus(payment.getId(), passenger.getId());

        assertThat(res.status()).isEqualTo(PaymentClientStatus.REFUND_PENDING);
        assertThat(res.updatedAt()).isEqualTo(updated);
        assertThat(res.instruction()).isNull();

        payment.setStatus(PaymentStatus.REFUNDED);
        assertThat(service.getStatus(payment.getId(), passenger.getId()).status()).isEqualTo(PaymentClientStatus.REFUNDED);
        payment.setStatus(PaymentStatus.INITIATED);
        booking.setStatus(BookingStatus.EXPIRED);
        assertThat(service.getStatus(payment.getId(), passenger.getId()).status()).isEqualTo(PaymentClientStatus.EXPIRED);
    }

    /** Constat F019 : rouvrir le widget reutilise le paiement INITIATED ; un nouveau n est cree qu apres un FAILED. */
    @Test
    void initiate_reusesTheInitiatedPayment_andCreatesANewOneOnlyAfterFailure() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment)).thenReturn(Optional.empty());

        InitiatePaymentResponse first = service.initiate(passenger.getId(), new InitiatePaymentRequest(booking.getId()));
        assertThat(first.paymentId()).isEqualTo(payment.getId());
        assertThat(first.transactionRef()).isEqualTo(payment.getProviderTxId());
        assertThat(first.amount()).isEqualTo(1000);
        verify(paymentRepository, never()).save(any());

        InitiatePaymentResponse second = service.initiate(passenger.getId(), new InitiatePaymentRequest(booking.getId()));
        assertThat(second.transactionRef()).startsWith("ekuiseo-booking-");
        verify(paymentRepository, times(1)).save(any());
    }

    /** Constat F149 : meme reutilisation pour un abonnement. */
    @Test
    void initiateSubscriptionPayment_reusesTheInitiatedPayment() {
        DriverSubscription subscription = DriverSubscription.builder().id(UUID.randomUUID())
                .driver(User.builder().id(UUID.randomUUID()).build()).priceFcfa(2000)
                .status(SubscriptionStatus.PENDING_PAYMENT).build();
        Payment existing = Payment.builder().id(UUID.randomUUID()).subscription(subscription)
                .providerTxId("ekuiseo-subscription-x").amount(2000).status(PaymentStatus.INITIATED).build();
        when(paymentRepository.findFirstBySubscriptionIdAndStatusOrderByCreatedAtDesc(subscription.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(existing));

        InitiatePaymentResponse res = service.initiateSubscriptionPayment(subscription);

        assertThat(res.paymentId()).isEqualTo(existing.getId());
        assertThat(res.transactionRef()).isEqualTo("ekuiseo-subscription-x");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void handleWebhook_reusesInitiatedPayment_andConfirms_withTheRealOperator() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(new KkiapayGateway.VerificationResult(
                true, "kk_123", 1000, 19, "SUCCESS", null, null, "CELTIIS CASH"));

        service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString())));

        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getChannel()).isEqualTo(PaymentChannel.CELTIIS);
        assertThat(payment.getRawPayload()).containsEntry("declaredMethod", "MOBILE_MONEY");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(paymentRepository).findByIdForUpdate(payment.getId());
    }

    /** V19 : sur un trajet a accord conducteur, l acompte encaisse met la reservation en attente du conducteur, pas CONFIRMED. */
    @Test
    @SuppressWarnings("unchecked")
    void handleWebhook_onANonInstantTrip_awaitsTheDriver_andAsksHimToAnswer() {
        Trip trip = booking.getTrip();
        trip.setInstantBooking(false);
        trip.setDepartureAt(Instant.now().plus(3, ChronoUnit.DAYS));
        trip.setOriginLabel("Cotonou");
        trip.setDestLabel("Parakou");
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString())));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_DRIVER_APPROVAL);
        assertThat(booking.getExpiresAt()).isNull();
        Instant expected = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(booking.getApprovalDeadlineAt()).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
        // Recu de paiement au passager, qui precise l attente ; demande critique au conducteur.
        ArgumentCaptor<Map<String, Object>> receipt = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.PAYMENT_SUCCEEDED), receipt.capture(), any());
        assertThat(receipt.getValue()).containsEntry("awaitingDriver", true).containsKey("approvalDeadlineAt");
        verify(notificationService).notifyCritical(eq(trip.getDriver()), eq(NotificationType.BOOKING_REQUESTED), any());
        verify(notificationService, never()).notify(eq(trip.getDriver()), eq(NotificationType.BOOKING_CONFIRMED), any());

        // Un second passage (widget apres webhook) ne touche plus a rien.
        service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString())));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_DRIVER_APPROVAL);
    }

    /** Constat F149 : le webhook d un abonnement reutilise lui aussi le paiement INITIATED. */
    @Test
    void handleWebhook_forASubscription_reusesTheInitiatedPayment_andActivatesIt() {
        DriverSubscription subscription = DriverSubscription.builder().id(UUID.randomUUID())
                .driver(User.builder().id(UUID.randomUUID()).build()).priceFcfa(2000)
                .status(SubscriptionStatus.PENDING_PAYMENT).build();
        Payment existing = Payment.builder().id(UUID.randomUUID()).subscription(subscription)
                .providerTxId("ekuiseo-subscription-x").amount(2000).status(PaymentStatus.INITIATED).build();
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.findActive(any(), any())).thenReturn(Optional.empty());
        when(paymentRepository.findFirstBySubscriptionIdAndStatusOrderByCreatedAtDesc(subscription.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(existing));
        when(paymentRepository.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 2000, "SUCCESS"));

        service.handleWebhook(webhook(Map.of("subscriptionId", subscription.getId().toString())));

        assertThat(existing.getProviderTxId()).isEqualTo("kk_123");
        assertThat(existing.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
    }

    /** Constat F012 : une cible inconnue est acquittee (log) sans verification payante, jamais 404. */
    @Test
    void handleWebhook_unknownBookingOrSubscription_isAcknowledgedWithoutVerification() {
        when(bookingRepository.findById(any())).thenReturn(Optional.empty());
        when(subscriptionRepository.findById(any())).thenReturn(Optional.empty());

        service.handleWebhook(webhook(Map.of("bookingId", UUID.randomUUID().toString())));
        service.handleWebhook(webhook(Map.of("subscriptionId", UUID.randomUUID().toString())));
        service.handleWebhook(webhook(Map.of()));

        verify(gateway, never()).verifyTransaction(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void handleWebhook_amountTooLow_doesNotConfirm() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 5, "SUCCESS"));

        service.handleWebhook(new KkiapayWebhookPayload("transaction.success", "kk_123", true, null,
                null, "MOBILE_MONEY", 5L, 0L, null, null, "{\"bookingId\":\"" + booking.getId() + "\"}"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    /** Constat F011 : un webhook non conclusif laisse le paiement INITIATED (avec l identifiant) et repond 503 pour un rejeu. */
    @Test
    void handleWebhook_pendingAtKkiapay_keepsInitiated_andAsksForReplay() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(false, 0, "PENDING"));

        assertThatThrownBy(() -> service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString()))))
                .isInstanceOf(bj.ekuiseo.api.service.payment.PaymentProviderUnavailableException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notifyCritical(any(), any(), any());
    }

    /** Constat F130 : un paiement rembourse, en cours de remboursement ou refuse est terminal ; le rejeu est ignore. */
    @Test
    void handleWebhook_onTerminalPayment_isIgnored() {
        for (PaymentStatus terminal : java.util.List.of(PaymentStatus.REFUNDED, PaymentStatus.REFUND_PENDING,
                PaymentStatus.REFUND_MANUAL, PaymentStatus.FAILED, PaymentStatus.SUCCEEDED)) {
            payment.setStatus(terminal);
            when(paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, "kk_123")).thenReturn(Optional.of(payment));

            service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString())));

            assertThat(payment.getStatus()).isEqualTo(terminal);
        }
        verify(gateway, never()).verifyTransaction(any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmFromWidget_onRefundedPayment_doesNotReverify() {
        payment.setStatus(PaymentStatus.REFUND_PENDING);

        service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        verify(gateway, never()).verifyTransaction(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    /** Constat F011 : la meme decision sert le widget et le webhook. */
    @Test
    void applyVerification_decidesSucceededFailedOrPending() {
        assertThat(service.applyVerification(payment, verified(true, 1000, "SUCCESS"), 1000, "t")).isEqualTo(PaymentService.Decision.SUCCEEDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        Payment insufficient = Payment.builder().status(PaymentStatus.INITIATED).build();
        assertThat(service.applyVerification(insufficient, verified(true, 5, "SUCCESS"), 1000, "t")).isEqualTo(PaymentService.Decision.FAILED);
        assertThat(insufficient.getStatus()).isEqualTo(PaymentStatus.FAILED);

        Payment pending = Payment.builder().status(PaymentStatus.INITIATED).build();
        assertThat(service.applyVerification(pending, verified(false, 0, "PROCESSING"), 1000, "t")).isEqualTo(PaymentService.Decision.PENDING);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(pending.getRawPayload()).containsEntry("decision", "PENDING").containsEntry("source", "t");

        Payment failed = Payment.builder().status(PaymentStatus.INITIATED).build();
        assertThat(service.applyVerification(failed, verified(false, 0, "FAILED"), 1000, "t")).isEqualTo(PaymentService.Decision.FAILED);
        assertThat(PaymentService.isTerminal(PaymentStatus.INITIATED)).isFalse();
        assertThat(PaymentService.isTerminal(PaymentStatus.REFUNDED)).isTrue();
    }

    @Test
    void handleWebhook_expiredBooking_isNotReconfirmed() {
        booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER); // expiree, places liberees
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        service.handleWebhook(webhook(Map.of("bookingId", booking.getId().toString())));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED); // l'argent a bien ete encaisse
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_PASSENGER);
        verify(bookingRepository, never()).save(any());
    }

    /** Constat F019 : le menage des paiements abandonnes delegue a la requete native et renvoie son compte. */
    @Test
    void failAbandonedInitiated_delegatesToTheRepository() {
        Instant before = Instant.now();
        when(paymentRepository.failAbandonedInitiated(before)).thenReturn(3);

        assertThat(service.failAbandonedInitiated(before)).isEqualTo(3);
    }

    @Test
    void parseChannel_recognisesOperatorsAndCards() {
        assertThat(PaymentService.parseChannel("MTN")).isEqualTo(PaymentChannel.MTN);
        assertThat(PaymentService.parseChannel("mtn momo")).isEqualTo(PaymentChannel.MTN);
        assertThat(PaymentService.parseChannel("MOOV MONEY")).isEqualTo(PaymentChannel.MOOV);
        assertThat(PaymentService.parseChannel("Celtiis")).isEqualTo(PaymentChannel.CELTIIS);
        assertThat(PaymentService.parseChannel("VISA")).isEqualTo(PaymentChannel.CARD);
        assertThat(PaymentService.parseChannel("MOBILE_MONEY")).isNull();
        assertThat(PaymentService.parseChannel(null)).isNull();
    }
}
