package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.LedgerService;
import bj.ekuiseo.api.service.RefundService;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrat A.3 sur une base reelle : deux decisions de remboursement simultanees pour le meme
 * paiement (annulation passager croisee avec une annulation de trajet, par exemple) ne creent
 * jamais deux remboursements. Le verrou pessimiste sur le paiement serialise les deux transactions ;
 * l index unique partiel {@code uq_refunds_live_payment} reste la garde finale. Le remboursement
 * unique part ensuite vers le fournisseur (simule) une seule fois, et le registre est contre-passe.
 */
class RefundConcurrencyIT extends AbstractPostgisIT {

    @MockitoBean
    private KkiapayGateway kkiapayGateway;

    @Autowired
    private RefundService refundService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private Booking booking;
    private Payment payment;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        when(kkiapayGateway.name()).thenReturn("KKIAPAY");
        when(kkiapayGateway.refundTransaction(anyString()))
                .thenReturn(new KkiapayGateway.RefundResult(true, "SUCCESS", "rembourse (simule)"));
        User driver = newUser("Awa", Role.USER);
        Vehicle vehicle = newVehicle(driver);
        User passenger = newUser("Jean", Role.USER);
        Trip trip = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Parakou", PARAKOU_LAT, PARAKOU_LNG,
                Instant.now().plus(3, ChronoUnit.DAYS), 3, 5000);
        booking = bookingRepository.save(Booking.builder().trip(trip).passenger(passenger).seats(1).amount(5000)
                .serviceFee(400).depositAmount(1000).balanceDueOnBoard(4000).status(BookingStatus.CANCELLED_BY_PASSENGER)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).build());
        payment = paymentRepository.save(Payment.builder().booking(booking).provider(PaymentProvider.KKIAPAY)
                .providerTxId("kkp-tx-refund-race").amount(1000).verifiedAmount(1000L).status(PaymentStatus.SUCCEEDED).build());
        transactionTemplate.executeWithoutResult(status -> ledgerService.recordPaymentSucceeded(
                paymentRepository.findById(payment.getId()).orElseThrow(), 1000, 19));
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void twoConcurrentRefundDecisions_createExactlyOneRefund() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<RefundService.RequestOutcome> f1 = pool.submit(() -> attempt("ANNULATION_PASSAGER", ready, start));
        Future<RefundService.RequestOutcome> f2 = pool.submit(() -> attempt("ANNULATION_CONDUCTEUR", ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<RefundService.RequestOutcome> outcomes = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));

        assertThat(outcomes.stream().filter(o -> o.status() == RefundService.RequestStatus.REQUESTED).count())
                .as("exactement une decision cree le remboursement").isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.status() == RefundService.RequestStatus.ALREADY_REQUESTED).count())
                .as("l autre retrouve le remboursement deja demande").isEqualTo(1);
        assertThat(refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId())).hasSize(1);

        // Execution unique apres validation, puis registre contre-passe.
        verify(kkiapayGateway, timeout(10_000).times(1)).refundTransaction("kkp-tx-refund-race");
        Thread.sleep(500);
        verify(kkiapayGateway, times(1)).refundTransaction("kkp-tx-refund-race");
        assertThat(refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId()).get(0).getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(ledgerEntryRepository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.REFUND)).hasSize(1);
        long reversed = ledgerEntryRepository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.DRIVER_SHARE_REVERSAL)
                .stream().mapToLong(e -> e.getAmountFcfa()).sum()
                + ledgerEntryRepository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.COMMISSION_REVERSAL)
                .stream().mapToLong(e -> e.getAmountFcfa()).sum();
        assertThat(reversed).isEqualTo(1000);
    }

    /** Le trigger d ajout seul refuse toute mise a jour ou suppression d une ecriture. */
    @Test
    void ledgerEntries_areAppendOnly() {
        assertThat(ledgerEntryRepository.findByPaymentIdAndEntryType(payment.getId(), LedgerEntryType.PASSENGER_PAYMENT)).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbcTemplate.update("delete from ledger_entries where payment_id = ?", payment.getId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbcTemplate.update("update ledger_entries set amount_fcfa = 1 where payment_id = ?", payment.getId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private RefundService.RequestOutcome attempt(String reason, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synchronisation des threads de test expiree");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return transactionTemplate.execute(status -> {
            Booking loaded = bookingRepository.findById(booking.getId()).orElseThrow();
            return refundService.requestForBooking(loaded, 1000, reason);
        });
    }
}
