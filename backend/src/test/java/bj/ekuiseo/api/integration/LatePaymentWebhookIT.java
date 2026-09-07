package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.service.RefundService;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regle metier n.6 (expiration a 20 min) croisee avec un webhook Kkiapay tardif
 * (constats F036/F105) : la reservation expiree a libere ses places, l'argent encaisse
 * apres coup ne doit PAS la reconfirmer (sur-reservation) mais repartir au passager.
 * Le paiement est simule par un mock de {@link KkiapayGateway} : le stub par defaut ne
 * renvoie aucun montant, or la confirmation exige un montant verifie suffisant.
 */
class LatePaymentWebhookIT extends AbstractPostgisIT {

    private static final String TX_ID = "kkp-tx-late-1";

    @MockitoBean
    private KkiapayGateway kkiapayGateway;

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    private Trip trip;
    private User passenger;

    @BeforeEach
    void setUp() {
        User driver = newUser("Awa", Role.USER);
        Vehicle vehicle = newVehicle(driver);
        passenger = newUser("Jean", Role.USER);
        trip = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Parakou", PARAKOU_LAT, PARAKOU_LNG,
                Instant.now().plus(3, ChronoUnit.DAYS), 3, 5000);
        when(kkiapayGateway.refundTransaction(anyString()))
                .thenReturn(new KkiapayGateway.RefundResult(true, "SUCCESS", "rembourse (simule)"));
    }

    @Test
    void lateWebhook_afterExpiry_refundsOrphanPaymentInsteadOfConfirming() {
        BookingResponse created = bookingService.createBooking(trip.getId(), passenger.getId(),
                new CreateBookingRequest(1, null, null, PaymentMethod.MOMO_DEPOSIT));
        InitiatePaymentResponse initiated = paymentService.initiate(passenger.getId(),
                new InitiatePaymentRequest(created.id()));
        long deposit = initiated.amount();
        assertThat(deposit).as("acompte = max(1000, 8 % de 5000)").isEqualTo(1000);
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getSeatsAvailable()).isEqualTo(2);

        // L acompte n est jamais arrive dans les 20 minutes : l echeance est depassee.
        jdbcTemplate.update("update bookings set expires_at = now() - interval '1 minute' where id = ?", created.id());
        assertThat(bookingService.expireStalePendingBookings()).isEqualTo(1);
        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getSeatsAvailable()).isEqualTo(3);

        // ... puis Kkiapay confirme un encaissement reel du bon montant.
        when(kkiapayGateway.verifyTransaction(TX_ID))
                .thenReturn(new KkiapayGateway.VerificationResult(true, TX_ID, deposit, 19, "SUCCESS", null, null));
        paymentService.handleWebhook(webhook(TX_ID, created.id(), deposit));

        Booking booking = bookingRepository.findById(created.id()).orElseThrow();
        assertThat(booking.getStatus()).as("jamais reconfirmee : les places ont ete rendues")
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getSeatsAvailable()).isEqualTo(3);

        List<Payment> payments = paymentRepository.findByBookingId(created.id());
        assertThat(payments).as("le paiement INITIATED est reutilise, pas duplique").hasSize(1);
        Payment payment = payments.get(0);
        assertThat(payment.getProviderTxId()).isEqualTo(TX_ID);
        assertThat(payment.getRefundReason()).isEqualTo(RefundService.REASON_ORPHAN);
        assertThat(payment.getRefundAmount()).isEqualTo(deposit);
        assertThat(payment.getStatus()).isIn(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);
        // Le remboursement part apres validation de la transaction, sur l executeur dedie.
        verify(kkiapayGateway, timeout(10_000)).refundTransaction(TX_ID);
        assertThat(paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, TX_ID)).isPresent();
    }

    @Test
    void timelyWebhook_confirmsBooking_andIsIdempotent() {
        BookingResponse created = bookingService.createBooking(trip.getId(), passenger.getId(),
                new CreateBookingRequest(2, null, null, PaymentMethod.MOMO_DEPOSIT));
        InitiatePaymentResponse initiated = paymentService.initiate(passenger.getId(),
                new InitiatePaymentRequest(created.id()));
        String txId = "kkp-tx-ontime-1";
        when(kkiapayGateway.verifyTransaction(txId))
                .thenReturn(new KkiapayGateway.VerificationResult(true, txId, initiated.amount(), 19, "SUCCESS", null, null));

        paymentService.handleWebhook(webhook(txId, created.id(), initiated.amount()));
        paymentService.handleWebhook(webhook(txId, created.id(), initiated.amount())); // rejeu Kkiapay

        Booking booking = bookingRepository.findById(created.id()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getExpiresAt()).isNull();
        Payment payment = paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, txId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getRefundReason()).isNull();
        verify(kkiapayGateway, times(1)).verifyTransaction(txId);
        verify(kkiapayGateway, never()).refundTransaction(anyString());
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getSeatsAvailable()).isEqualTo(1);
    }

    /** Un montant verifie inferieur a l attendu ne confirme rien : l argent encaisse est rembourse. */
    @Test
    void webhookWithInsufficientVerifiedAmount_doesNotConfirm_andRefunds() {
        BookingResponse created = bookingService.createBooking(trip.getId(), passenger.getId(),
                new CreateBookingRequest(1, null, null, PaymentMethod.MOMO_FULL));
        InitiatePaymentResponse initiated = paymentService.initiate(passenger.getId(),
                new InitiatePaymentRequest(created.id()));
        assertThat(initiated.amount()).isEqualTo(5000);
        String txId = "kkp-tx-short-1";
        when(kkiapayGateway.verifyTransaction(txId))
                .thenReturn(new KkiapayGateway.VerificationResult(true, txId, 5, 0, "SUCCESS", null, null));

        paymentService.handleWebhook(webhook(txId, created.id(), 5));

        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        Payment payment = paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, txId).orElseThrow();
        assertThat(payment.getRefundReason()).isEqualTo(RefundService.REASON_AMOUNT_INSUFFICIENT);
        assertThat(payment.getRefundAmount()).isEqualTo(5);
        verify(kkiapayGateway, timeout(10_000)).refundTransaction(txId);
    }

    private static KkiapayWebhookPayload webhook(String txId, java.util.UUID bookingId, long amount) {
        return new KkiapayWebhookPayload("transaction.success", txId, true, "22901970000", "Ekuiseo",
                "MOBILE_MONEY", amount, 19L, "partner", Instant.now(),
                Map.of("bookingId", bookingId.toString()));
    }
}
