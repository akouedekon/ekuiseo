package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.payout.DriverBalanceResponse;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.mapper.PayoutMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutItemRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie le calcul des reversements conducteurs (regle metier n.12, corrigee
 * par la regle n.21 - paiement fractionne) : le montant net reverse est
 * (booking.amount - booking.serviceFee) en MOMO_FULL, mais
 * (booking.depositAmount - booking.serviceFee) en MOMO_DEPOSIT - la plateforme
 * ne reverse jamais plus qu'elle n'a reellement encaisse en ligne. Seuls les
 * conducteurs dont le solde ainsi calcule atteint le seuil minimum configure
 * sont inclus dans un lot ({@link PayoutService#runWeeklyBatch}).
 */
class PayoutServiceTest {

    private static final List<BookingStatus> PAYABLE_STATUSES = List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    private static final List<PaymentMethod> PAYABLE_METHODS = List.of(PaymentMethod.MOMO_DEPOSIT, PaymentMethod.MOMO_FULL);

    /** Reservation payee integralement en ligne (MOMO_FULL) : depositAmount = amount. */
    private Booking momoFullBooking(long amount, long fee) {
        return payableBooking(amount, fee, amount, PaymentMethod.MOMO_FULL);
    }

    /** Reservation avec un acompte partiel (MOMO_DEPOSIT) : depositAmount < amount. */
    private Booking momoDepositBooking(long amount, long fee, long depositAmount) {
        return payableBooking(amount, fee, depositAmount, PaymentMethod.MOMO_DEPOSIT);
    }

    private Booking payableBooking(long amount, long fee, long depositAmount, PaymentMethod method) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .seats(1)
                .amount(amount)
                .serviceFee(fee)
                .depositAmount(depositAmount)
                .balanceDueOnBoard(amount - depositAmount)
                .status(BookingStatus.CONFIRMED)
                .paymentMethod(method)
                .build();
        b.setCreatedAt(java.time.Instant.now().minusSeconds(3600));
        return b;
    }

    @Test
    void getBalance_sumsNetAmount_ofPayableBookings_perPaymentMethod() {
        UUID driverId = UUID.randomUUID();
        BookingRepository bookingRepository = mock(BookingRepository.class);
        DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
        DriverPayoutItemRepository driverPayoutItemRepository = mock(DriverPayoutItemRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
        PayoutMapper payoutMapper = mock(PayoutMapper.class);
        AuditService auditService = mock(AuditService.class);

        when(bookingRepository.findPayableForDriver(eq(driverId), eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS)))
                .thenReturn(List.of(momoFullBooking(2000, 160), momoDepositBooking(3000, 240, 1000)));

        PayoutService payoutService = new PayoutService(bookingRepository, driverPayoutRepository,
                driverPayoutItemRepository, userRepository, paymentAccountRepository, payoutMapper, auditService, 2000);

        DriverBalanceResponse balance = payoutService.getBalance(driverId);

        // MOMO_FULL : 2000-160 = 1840 (net = amount - serviceFee)
        // MOMO_DEPOSIT : 1000-240 = 760 (net = depositAmount - serviceFee, PAS amount - serviceFee = 2760)
        assertThat(balance.pendingBalanceFcfa()).isEqualTo(2600);
        assertThat(balance.minimumPayoutThresholdFcfa()).isEqualTo(2000);
    }

    @Test
    void runWeeklyBatch_includesOnlyDriversAboveMinimumThreshold() {
        UUID adminId = UUID.randomUUID();
        UUID driverBelow = UUID.randomUUID();
        UUID driverEligible = UUID.randomUUID(); // net = 2600 -> inclus

        BookingRepository bookingRepository = mock(BookingRepository.class);
        DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
        DriverPayoutItemRepository driverPayoutItemRepository = mock(DriverPayoutItemRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
        PayoutMapper payoutMapper = mock(PayoutMapper.class);
        AuditService auditService = mock(AuditService.class);

        when(bookingRepository.findDriverIdsWithPayableBookings(eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS)))
                .thenReturn(List.of(driverBelow, driverEligible));

        // driverBelow : une seule reservation MOMO_FULL, net = 2000-160 = 1840, sous le
        // seuil de 2000 -> reporte au prochain lot.
        when(bookingRepository.findPayableForDriver(eq(driverBelow), eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS)))
                .thenReturn(List.of(momoFullBooking(2000, 160)));

        // driverEligible : une reservation MOMO_FULL (net 1840) + une MOMO_DEPOSIT dont
        // seul l'acompte de 1000 a ete encaisse (net = 1000-240 = 760, PAS 3000-240=2760) ->
        // total 2600, au-dessus du seuil -> reversement cree pour 2600, pas 4600.
        when(bookingRepository.findPayableForDriver(eq(driverEligible), eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS)))
                .thenReturn(List.of(momoFullBooking(2000, 160), momoDepositBooking(3000, 240, 1000)));

        User eligibleDriver = User.builder().id(driverEligible).phone("+22997000009").build();
        when(userRepository.findById(driverEligible)).thenReturn(Optional.of(eligibleDriver));
        when(driverPayoutRepository.save(any(DriverPayout.class))).thenAnswer(inv -> {
            DriverPayout p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(payoutMapper.toResponse(any(DriverPayout.class))).thenAnswer(inv -> {
            DriverPayout p = inv.getArgument(0);
            return new PayoutResponse(p.getId(), p.getDriver().getId(), p.getAmount(), p.getStatus(),
                    p.getDestinationMsisdn(), p.getPeriodStart(), p.getPeriodEnd(), p.getRequestedAt(), p.getSettledAt());
        });

        PayoutService payoutService = new PayoutService(bookingRepository, driverPayoutRepository,
                driverPayoutItemRepository, userRepository, paymentAccountRepository, payoutMapper, auditService, 2000);

        PayoutBatchResultResponse result = payoutService.runWeeklyBatch(adminId);

        assertThat(result.payoutsCreated()).isEqualTo(1);
        assertThat(result.totalAmountFcfa()).isEqualTo(2600);
        assertThat(result.payouts()).extracting(PayoutResponse::driverId).containsExactly(driverEligible);

        ArgumentCaptor<DriverPayout> payoutCaptor = ArgumentCaptor.forClass(DriverPayout.class);
        verify(driverPayoutRepository).save(payoutCaptor.capture());
        assertThat(payoutCaptor.getValue().getAmount()).isEqualTo(2600);
        assertThat(payoutCaptor.getValue().getDriver().getId()).isEqualTo(driverEligible);

        // driverBelow n'a jamais donne lieu a un appel a userRepository.findById (exclu avant)
        verify(userRepository, never()).findById(driverBelow);
        verify(driverPayoutItemRepository).saveAll(anyList());
    }

    @Test
    void netAmount_neverNegative_becauseDepositAlwaysCoversServiceFee() {
        // Regle metier n.21 (point 2, le "point delicat") : FeePolicy#computeDepositAmount
        // garantit deposit >= serviceFee. Ce test verifie que PayoutService en tire bien
        // un net jamais negatif meme quand la commission (ex: taux eleve sur un gros
        // montant) approcherait ou depasserait un acompte de base bas - via une reservation
        // ou l'acompte encaisse est deliberement tres proche des frais de service.
        UUID driverId = UUID.randomUUID();
        BookingRepository bookingRepository = mock(BookingRepository.class);
        DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
        DriverPayoutItemRepository driverPayoutItemRepository = mock(DriverPayoutItemRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
        PayoutMapper payoutMapper = mock(PayoutMapper.class);
        AuditService auditService = mock(AuditService.class);

        // serviceFee (1200) > acompte de base habituel (1000) : deposit doit avoir ete
        // calcule comme max(1000, 1200) = 1200 a la creation (voir FeePolicy), jamais 1000.
        when(bookingRepository.findPayableForDriver(eq(driverId), eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS)))
                .thenReturn(List.of(momoDepositBooking(15_000, 1200, 1200)));

        PayoutService payoutService = new PayoutService(bookingRepository, driverPayoutRepository,
                driverPayoutItemRepository, userRepository, paymentAccountRepository, payoutMapper, auditService, 2000);

        DriverBalanceResponse balance = payoutService.getBalance(driverId);

        assertThat(balance.pendingBalanceFcfa()).isEqualTo(0); // 1200 - 1200 = 0, jamais negatif
    }
}
