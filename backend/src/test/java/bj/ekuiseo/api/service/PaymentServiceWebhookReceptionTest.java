package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.CashStatus;
import bj.ekuiseo.api.domain.enums.PaymentEventSource;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.WebhookOutcome;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrat A.5 sur PaymentService#receiveWebhook : le corps est enregistre avant tout traitement ;
 * un webhook rejoue deux fois n est traite qu une fois ; une signature invalide est enregistree
 * REJECTED et ne fait rien bouger ; un paiement verifie ecrit ses evenements et son registre.
 */
class PaymentServiceWebhookReceptionTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final KkiapayGateway gateway = mock(KkiapayGateway.class);
    private final RefundService refundService = mock(RefundService.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentEventService paymentEventService = mock(PaymentEventService.class);
    private final PaymentWebhookService webhookService = mock(PaymentWebhookService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final org.springframework.transaction.PlatformTransactionManager txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    private PaymentService service;
    private Booking booking;
    private Payment payment;
    private String rawBody;

    @BeforeEach
    void setUp() {
        when(gateway.name()).thenReturn("KKIAPAY");
        when(txManager.getTransaction(any(org.springframework.transaction.TransactionDefinition.class))).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new PaymentService(paymentRepository, bookingRepository, mock(DriverSubscriptionRepository.class),
                notificationService, mock(AuditService.class), gateway, refundService,
                new KkiapayWebhookParser(new ObjectMapper()), new DriverApprovalPolicy(24),
                ledgerService, paymentEventService, webhookService, txManager, "pk_test", "secret-hash", true);
        User passenger = User.builder().id(UUID.randomUUID()).firstName("Jean").build();
        User driver = User.builder().id(UUID.randomUUID()).build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).originLabel("Cotonou").destLabel("Parakou")
                .departureAt(java.time.Instant.now().plusSeconds(86_400)).build();
        booking = Booking.builder().id(UUID.randomUUID()).passenger(passenger).trip(trip).seats(1).amount(4000)
                .serviceFee(320).depositAmount(1000).balanceDueOnBoard(3000).status(BookingStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).build();
        payment = Payment.builder().id(UUID.randomUUID()).booking(booking).provider(PaymentProvider.KKIAPAY)
                .providerTxId("ekuiseo-booking-x").amount(1000).status(PaymentStatus.INITIATED).build();
        rawBody = "{\"event\":\"transaction.success\",\"transactionId\":\"kk_123\",\"isPaymentSucces\":true,"
                + "\"amount\":1000,\"fees\":19,\"stateData\":{\"bookingId\":\"" + booking.getId() + "\"}}";
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.findByProviderAndProviderTxId(eq(PaymentProvider.KKIAPAY), any())).thenReturn(Optional.empty());
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.verifyTransaction("kk_123")).thenReturn(new KkiapayGateway.VerificationResult(true, "kk_123", 1000, 19, "SUCCESS", null, null, "MTN"));
    }

    @Test
    void replayedTwice_isProcessedOnce_andRecordedAsDuplicate() {
        UUID eventId = UUID.randomUUID();
        when(webhookService.register(eq("KKIAPAY"), eq("kk_123"), any(), anyString(), eq(true)))
                .thenReturn(new PaymentWebhookService.Registration(PaymentWebhookService.Decision.PROCESS, eventId))
                .thenReturn(new PaymentWebhookService.Registration(PaymentWebhookService.Decision.DUPLICATE, UUID.randomUUID()));

        service.receiveWebhook(rawBody, "secret-hash");
        service.receiveWebhook(rawBody, "secret-hash");

        verify(gateway, times(1)).verifyTransaction("kk_123");
        verify(webhookService, times(2)).register(eq("KKIAPAY"), eq("kk_123"), any(), eq(PaymentWebhookService.hash(rawBody)), eq(true));
        verify(webhookService).complete(eventId, WebhookOutcome.PROCESSED, null);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        // Solde a bord attendu des la confirmation (contrat A.6).
        assertThat(booking.getCashStatus()).isEqualTo(CashStatus.EXPECTED);
        assertThat(booking.getCashExpectedFcfa()).isEqualTo(3000);
        // Registre et evenement de verification (contrat A.4 / A.5).
        verify(ledgerService).recordPaymentSucceeded(payment, 1000, 19);
        verify(paymentEventService).record(eq(payment), eq(PaymentEventService.VERIFIED), eq(PaymentStatus.INITIATED),
                eq(PaymentStatus.SUCCEEDED), eq(PaymentEventSource.WEBHOOK), eq(null), any());
    }

    @Test
    void invalidSignature_isRecordedRejected_andNothingMoves() {
        when(webhookService.register(eq("KKIAPAY"), eq("kk_123"), any(), anyString(), eq(false)))
                .thenReturn(new PaymentWebhookService.Registration(PaymentWebhookService.Decision.REJECTED, UUID.randomUUID()));

        service.receiveWebhook(rawBody, "mauvais-secret");
        service.receiveWebhook(rawBody, null);

        verify(webhookService, times(2)).register(eq("KKIAPAY"), eq("kk_123"), any(), anyString(), eq(false));
        verify(gateway, never()).verifyTransaction(any());
        verify(paymentRepository, never()).save(any());
        verify(webhookService, never()).complete(any(), any(), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    /** Verification non conclusive : ERROR enregistre, 503 rendu (rejeu demande), rien de confirme. */
    @Test
    void inconclusiveVerification_isRecordedAsError_andRethrown() {
        UUID eventId = UUID.randomUUID();
        when(webhookService.register(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(new PaymentWebhookService.Registration(PaymentWebhookService.Decision.PROCESS, eventId));
        when(gateway.verifyTransaction("kk_123")).thenReturn(new KkiapayGateway.VerificationResult(false, "kk_123", 0, 0, "PENDING", null, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.receiveWebhook(rawBody, "secret-hash"))
                .isInstanceOf(bj.ekuiseo.api.service.payment.PaymentProviderUnavailableException.class);

        verify(webhookService).complete(eq(eventId), eq(WebhookOutcome.ERROR), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
    }

    /** Cible inconnue : IGNORED, acquitte, aucune verification payante. */
    @Test
    void unknownTarget_isRecordedIgnored() {
        UUID eventId = UUID.randomUUID();
        when(webhookService.register(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(new PaymentWebhookService.Registration(PaymentWebhookService.Decision.PROCESS, eventId));
        String unknown = "{\"transactionId\":\"kk_999\",\"stateData\":{\"bookingId\":\"" + UUID.randomUUID() + "\"}}";
        when(bookingRepository.findById(any())).thenReturn(Optional.empty());

        service.receiveWebhook(unknown, "secret-hash");

        verify(webhookService).complete(eventId, WebhookOutcome.IGNORED, null);
        verify(gateway, never()).verifyTransaction(any());
    }
}
