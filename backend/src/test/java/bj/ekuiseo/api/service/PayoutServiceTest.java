package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.DriverPayoutItem;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.dto.payout.DriverBalanceResponse;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.mapper.PayoutMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutItemRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reversements conducteurs (regle metier n.12 corrigee par la n.21) et lot 1.2 de
 * l audit : net = ce qui a reellement ete encaisse, destination = compte mobile money
 * verifie, reservation remboursee retiree du lot.
 */
class PayoutServiceTest {

    private static final List<BookingStatus> PAYABLE_STATUSES = List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    private static final List<PaymentMethod> PAYABLE_METHODS = List.of(PaymentMethod.MOMO_DEPOSIT, PaymentMethod.MOMO_FULL);

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
    private final DriverPayoutItemRepository driverPayoutItemRepository = mock(DriverPayoutItemRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
    private final PayoutMapper payoutMapper = mock(PayoutMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private PayoutService service;

    @BeforeEach
    void setUp() {
        service = new PayoutService(bookingRepository, driverPayoutRepository, driverPayoutItemRepository,
                userRepository, paymentAccountRepository, payoutMapper, auditService, notificationService, 2000, 24);
        when(driverPayoutRepository.save(any(DriverPayout.class))).thenAnswer(inv -> {
            DriverPayout p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(payoutMapper.toResponse(any(DriverPayout.class))).thenAnswer(inv -> {
            DriverPayout p = inv.getArgument(0);
            return new PayoutResponse(p.getId(), p.getDriver().getId(), p.getAmount(), p.getStatus(),
                    p.getDestinationMsisdn(), p.getPeriodStart(), p.getPeriodEnd(), p.getRequestedAt(), p.getSettledAt());
        });
    }

    private static Booking momoFullBooking(long amount, long fee) {
        return payableBooking(amount, fee, amount, PaymentMethod.MOMO_FULL);
    }

    private static Booking momoDepositBooking(long amount, long fee, long depositAmount) {
        return payableBooking(amount, fee, depositAmount, PaymentMethod.MOMO_DEPOSIT);
    }

    private static Booking payableBooking(long amount, long fee, long depositAmount, PaymentMethod method) {
        Booking b = Booking.builder().id(UUID.randomUUID()).seats(1).amount(amount).serviceFee(fee)
                .depositAmount(depositAmount).balanceDueOnBoard(amount - depositAmount)
                .status(BookingStatus.CONFIRMED).paymentMethod(method).build();
        b.setCreatedAt(Instant.now().minusSeconds(3600));
        return b;
    }

    private static PaymentAccount verifiedAccount(User driver) {
        return PaymentAccount.builder().id(UUID.randomUUID()).user(driver).provider(MobileMoneyOperator.MOOV_MONEY)
                .phone("+2290155000001").isDefault(true).verifiedAt(Instant.now()).build();
    }

    @Test
    void getBalance_sumsNetAmount_ofPayableBookings_perPaymentMethod() {
        UUID driverId = UUID.randomUUID();
        when(bookingRepository.findPayableForDriver(eq(driverId), eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS), any()))
                .thenReturn(List.of(momoFullBooking(2000, 160), momoDepositBooking(3000, 240, 1000)));

        DriverBalanceResponse balance = service.getBalance(driverId);

        // MOMO_FULL : 2000-160 = 1840 ; MOMO_DEPOSIT : 1000-240 = 760 (jamais 3000-240)
        assertThat(balance.pendingBalanceFcfa()).isEqualTo(2600);
        assertThat(balance.minimumPayoutThresholdFcfa()).isEqualTo(2000);
    }

    @Test
    void getBalance_usesTheEligibilityCutoff() {
        UUID driverId = UUID.randomUUID();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(bookingRepository.findPayableForDriver(eq(driverId), any(), any(), cutoff.capture())).thenReturn(List.of());

        service.getBalance(driverId);

        // 24 h avant maintenant : les trajets non encore partis ne sont jamais reversables (F006/F102).
        assertThat(cutoff.getValue()).isBetween(Instant.now().minusSeconds(24 * 3600 + 60), Instant.now().minusSeconds(24 * 3600 - 60));
    }

    @Test
    void runWeeklyBatch_includesOnlyDriversAboveThreshold_andUsesTheVerifiedAccount() {
        UUID adminId = UUID.randomUUID();
        UUID driverBelow = UUID.randomUUID();
        UUID driverEligible = UUID.randomUUID();
        when(bookingRepository.findDriverIdsWithPayableBookings(eq(PAYABLE_STATUSES), eq(PAYABLE_METHODS), any()))
                .thenReturn(List.of(driverBelow, driverEligible));
        when(bookingRepository.findPayableForDriver(eq(driverBelow), any(), any(), any()))
                .thenReturn(List.of(momoFullBooking(2000, 160)));
        when(bookingRepository.findPayableForDriver(eq(driverEligible), any(), any(), any()))
                .thenReturn(List.of(momoFullBooking(2000, 160), momoDepositBooking(3000, 240, 1000)));
        User eligibleDriver = User.builder().id(driverEligible).phone("+2290197000009").firstName("A").lastName("B").build();
        when(userRepository.findById(driverEligible)).thenReturn(Optional.of(eligibleDriver));
        when(paymentAccountRepository.findByUserIdAndIsDefaultTrue(driverEligible)).thenReturn(Optional.of(verifiedAccount(eligibleDriver)));

        PayoutBatchResultResponse result = service.runWeeklyBatch(adminId);

        assertThat(result.payoutsCreated()).isEqualTo(1);
        assertThat(result.totalAmountFcfa()).isEqualTo(2600);
        assertThat(result.skipped()).isEmpty();
        ArgumentCaptor<DriverPayout> payoutCaptor = ArgumentCaptor.forClass(DriverPayout.class);
        verify(driverPayoutRepository).save(payoutCaptor.capture());
        // Destination = compte mobile money verifie, pas le numero de connexion (F103/F602).
        assertThat(payoutCaptor.getValue().getDestinationMsisdn()).isEqualTo("+2290155000001");
        assertThat(payoutCaptor.getValue().getDestinationProvider()).isEqualTo(MobileMoneyOperator.MOOV_MONEY);
        verify(userRepository, never()).findById(driverBelow);
        verify(driverPayoutItemRepository).saveAll(anyList());
    }

    @Test
    void runWeeklyBatch_skipsAndNotifiesDriversWithoutVerifiedAccount() {
        UUID driverId = UUID.randomUUID();
        User driver = User.builder().id(driverId).phone("+2290197000009").firstName("Koffi").lastName("A").build();
        when(bookingRepository.findDriverIdsWithPayableBookings(any(), any(), any())).thenReturn(List.of(driverId));
        when(bookingRepository.findPayableForDriver(eq(driverId), any(), any(), any()))
                .thenReturn(List.of(momoFullBooking(5000, 400)));
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        // Compte par defaut present mais NON verifie : ne sert pas de destination.
        PaymentAccount unverified = PaymentAccount.builder().id(UUID.randomUUID()).user(driver)
                .provider(MobileMoneyOperator.MTN_MOMO).phone("+2290166000000").isDefault(true).build();
        when(paymentAccountRepository.findByUserIdAndIsDefaultTrue(driverId)).thenReturn(Optional.of(unverified));

        PayoutBatchResultResponse result = service.runWeeklyBatch(UUID.randomUUID());

        assertThat(result.payoutsCreated()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).amountFcfa()).isEqualTo(4600);
        assertThat(result.skipped().get(0).reason()).isEqualTo(PayoutService.SKIP_NO_ACCOUNT);
        verify(driverPayoutRepository, never()).save(any());
        verify(notificationService).notify(eq(driver), eq(NotificationType.PAYOUT_ACCOUNT_MISSING), any());
    }

    @Test
    void excludeCancelledBooking_removesItemFromPendingLot_orMarksReversalOnSettledLot() {
        User driver = User.builder().id(UUID.randomUUID()).build();
        Booking booking = momoFullBooking(2000, 160);
        DriverPayout pending = DriverPayout.builder().id(UUID.randomUUID()).driver(driver).amount(4440)
                .status(PayoutStatus.PENDING).build();
        DriverPayoutItem item = DriverPayoutItem.builder().id(UUID.randomUUID()).payout(pending)
                .bookingId(booking.getId()).netAmount(1840).build();
        when(driverPayoutItemRepository.findByBookingId(booking.getId())).thenReturn(Optional.of(item));
        when(driverPayoutItemRepository.countByPayoutId(pending.getId())).thenReturn(2L);

        service.excludeCancelledBooking(booking, "ANNULATION_PASSAGER");

        verify(driverPayoutItemRepository).delete(item);
        assertThat(pending.getAmount()).isEqualTo(2600);
        verify(driverPayoutRepository).save(pending);

        // Lot deja regle : rien n est supprime, l item est marque a deduire et audite.
        DriverPayout settled = DriverPayout.builder().id(UUID.randomUUID()).driver(driver).amount(1840)
                .status(PayoutStatus.SETTLED).build();
        Booking other = momoFullBooking(2000, 160);
        DriverPayoutItem settledItem = DriverPayoutItem.builder().id(UUID.randomUUID()).payout(settled)
                .bookingId(other.getId()).netAmount(1840).build();
        when(driverPayoutItemRepository.findByBookingId(other.getId())).thenReturn(Optional.of(settledItem));

        service.excludeCancelledBooking(other, "ANNULATION_CONDUCTEUR");

        assertThat(settledItem.getReversedAt()).isNotNull();
        assertThat(settledItem.getReversalReason()).isEqualTo("ANNULATION_CONDUCTEUR");
        verify(driverPayoutItemRepository, never()).delete(settledItem);
        verify(auditService).log(any(), eq("PAYOUT_ALREADY_INCLUDED"), eq("driver_payout"), eq(settled.getId()), any());
    }

    @Test
    void settle_refusesAlreadySettledOrDestinationlessLots() {
        User driver = User.builder().id(UUID.randomUUID()).build();
        DriverPayout settled = DriverPayout.builder().id(UUID.randomUUID()).driver(driver).amount(100)
                .status(PayoutStatus.SETTLED).destinationMsisdn("+2290155000001").build();
        when(driverPayoutRepository.findById(settled.getId())).thenReturn(Optional.of(settled));
        assertThatThrownBy(() -> service.settle(UUID.randomUUID(), settled.getId())).isInstanceOf(ConflictException.class);

        DriverPayout noDestination = DriverPayout.builder().id(UUID.randomUUID()).driver(driver).amount(100)
                .status(PayoutStatus.PENDING).build();
        when(driverPayoutRepository.findById(noDestination.getId())).thenReturn(Optional.of(noDestination));
        assertThatThrownBy(() -> service.settle(UUID.randomUUID(), noDestination.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void netAmount_neverNegative_becauseDepositAlwaysCoversServiceFee() {
        UUID driverId = UUID.randomUUID();
        when(bookingRepository.findPayableForDriver(eq(driverId), any(), any(), any()))
                .thenReturn(List.of(momoDepositBooking(15_000, 1200, 1200)));

        assertThat(service.getBalance(driverId).pendingBalanceFcfa()).isEqualTo(0);
    }
}
