package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.dto.payment.PaymentStatusResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmation d'un paiement depuis le widget Kkiapay (PaymentService#confirmFromWidget)
 * et traitement du webhook : dans les deux cas, rien n'est cru sur parole - statut ET
 * montant sont reverifies aupres de Kkiapay avant de confirmer la reservation.
 */
class PaymentServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final DriverSubscriptionRepository subscriptionRepository = mock(DriverSubscriptionRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final KkiapayGateway gateway = mock(KkiapayGateway.class);
    private final RefundService refundService = mock(RefundService.class);

    private PaymentService service;
    private User passenger;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, bookingRepository, subscriptionRepository,
                notificationService, auditService, gateway, refundService, "pk_test", "secret", true);
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
        when(paymentRepository.findByProviderAndProviderTxId(eq(PaymentProvider.KKIAPAY), any()))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static KkiapayGateway.VerificationResult verified(boolean success, long amount, String raw) {
        return new KkiapayGateway.VerificationResult(success, "kk_123", amount, 19, raw, null, null);
    }

    @Test
    void confirmFromWidget_verifiedAndSufficient_confirmsBooking() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), " kk_123 ");

        assertThat(res.status()).isEqualTo("SUCCEEDED");
        assertThat(res.transactionRef()).isEqualTo("kk_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(payment.getFee()).isEqualTo(19);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(booking);
    }

    @Test
    void confirmFromWidget_amountTooLow_doesNotConfirm() {
        // Le widget a ete ouvert avec 5 F au lieu de 1 000 F : Kkiapay dit SUCCESS, nous non.
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 5, "SUCCESS"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo("FAILED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void confirmFromWidget_pendingAtKkiapay_keepsWaitingForWebhook() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(false, 0, "PENDING"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo("PROCESSING");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmFromWidget_finalFailure_marksFailed() {
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(false, 1000, "FAILED"));

        PaymentStatusResponse res = service.confirmFromWidget(payment.getId(), passenger.getId(), "kk_123");

        assertThat(res.status()).isEqualTo("FAILED");
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

        assertThat(res.status()).isEqualTo("SUCCEEDED");
        verify(gateway, never()).verifyTransaction(any());
    }

    @Test
    void handleWebhook_reusesInitiatedPayment_andConfirms() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        service.handleWebhook(new KkiapayWebhookPayload("transaction.success", "kk_123", true, "22997000000",
                "Ekuiseo", "MOBILE_MONEY", 1000L, 19L, null, null, Map.of("bookingId", booking.getId().toString())));

        assertThat(payment.getProviderTxId()).isEqualTo("kk_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
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

    @Test
    void handleWebhook_expiredBooking_isNotReconfirmed() {
        booking.setStatus(BookingStatus.CANCELLED_BY_PASSENGER); // expiree, places liberees
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED))
                .thenReturn(Optional.of(payment));
        when(gateway.verifyTransaction("kk_123")).thenReturn(verified(true, 1000, "SUCCESS"));

        service.handleWebhook(new KkiapayWebhookPayload("transaction.success", "kk_123", true, null,
                null, "MOBILE_MONEY", 1000L, 19L, null, null, Map.of("bookingId", booking.getId().toString())));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED); // l'argent a bien ete encaisse
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED_BY_PASSENGER);
        verify(bookingRepository, never()).save(any());
    }
}
