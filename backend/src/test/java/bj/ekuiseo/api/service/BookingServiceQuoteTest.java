package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifie POST /api/v1/trips/{id}/booking-quote (BookingService#quote, regle
 * metier n.21) : le devis doit refuser proprement (trajet non PUBLISHED, complet,
 * ou conducteur qui se cote lui-meme) et, surtout, produire EXACTEMENT le meme
 * plan de paiement que createBooking produirait pour les memes parametres - via
 * la meme FeePolicy (reelle ici, pas mockee, pour verifier cette parite pour de
 * vrai plutot que de la supposer).
 */
class BookingServiceQuoteTest {

    private static final FeePolicy REAL_FEE_POLICY = new FeePolicy(0.08, 5, 1000);

    private BookingService newService(TripRepository tripRepository, DriverSubscriptionRepository driverSubscriptionRepository) {
        return new BookingService(mock(BookingRepository.class), tripRepository, mock(bj.ekuiseo.api.repository.TripStopRepository.class), mock(UserRepository.class),
                driverSubscriptionRepository, mock(MessageRepository.class), mock(bj.ekuiseo.api.repository.ReviewRepository.class), mock(BookingMapper.class),
                new CancellationPolicy(), new DriverCancellationPolicy(), mock(NotificationService.class),
                mock(PaymentService.class), mock(AuditService.class), REAL_FEE_POLICY, 20);
    }

    private Trip publishedTrip(UUID driverId, long pricePerSeat, int seatsAvailable) {
        User driver = User.builder().id(driverId).build();
        return Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.PUBLISHED)
                .pricePerSeat(pricePerSeat).seatsAvailable(seatsAvailable)
                .departureAt(Instant.now().plus(3, ChronoUnit.DAYS)).build();
    }

    @Test
    void quote_rejectsNonPublishedTrip() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 1000, 5);
        trip.setStatus(TripStatus.CANCELLED);
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(1, null, PaymentMethod.MOMO_DEPOSIT);

        assertThatThrownBy(() -> service.quote(trip.getId(), UUID.randomUUID(), req))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void quote_rejectsWhenRequesterIsTheDriver() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        UUID driverId = UUID.randomUUID();
        Trip trip = publishedTrip(driverId, 1000, 5);
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(1, null, PaymentMethod.MOMO_DEPOSIT);

        assertThatThrownBy(() -> service.quote(trip.getId(), driverId, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void quote_rejectsWhenNotEnoughSeats() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 1000, 1); // une seule place disponible
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(2, null, PaymentMethod.MOMO_DEPOSIT); // en demande 2

        assertThatThrownBy(() -> service.quote(trip.getId(), UUID.randomUUID(), req))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void quote_momoDeposit_matchesFeePolicyExactly() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 5000, 5); // 3 places * 5000 = 15000
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(driverSubscriptionRepository.hasActiveSubscription(any(), any())).thenReturn(false);

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(3, null, PaymentMethod.MOMO_DEPOSIT);

        PaymentPlanResponse plan = service.quote(trip.getId(), UUID.randomUUID(), req);

        // 15000 * 8% = 1200, > acompte de base (1000) -> deposit = max(1000, 1200) = 1200
        assertThat(plan.totalAmount()).isEqualTo(15_000);
        assertThat(plan.serviceFee()).isEqualTo(1_200);
        assertThat(plan.depositAmount()).isEqualTo(1_200);
        assertThat(plan.balanceAmount()).isEqualTo(13_800);
        assertThat(plan.paymentMethod()).isEqualTo(PaymentMethod.MOMO_DEPOSIT);
        assertThat(plan.paymentStatus()).isEqualTo("PENDING");
        assertThat(plan.depositDueAt()).isNotNull();
        assertThat(plan.freeCancellationHours()).isEqualTo(24);
    }

    @Test
    void quote_defaultsToMomoDeposit_whenPaymentModeAbsent() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 800, 5); // 1 * 800, < acompte de base
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(driverSubscriptionRepository.hasActiveSubscription(any(), any())).thenReturn(false);

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(1, null, null); // paymentMode absent

        PaymentPlanResponse plan = service.quote(trip.getId(), UUID.randomUUID(), req);

        assertThat(plan.paymentMethod()).isEqualTo(PaymentMethod.MOMO_DEPOSIT);
        // acompte plafonne au montant total (800 < 1000)
        assertThat(plan.depositAmount()).isEqualTo(800);
        assertThat(plan.balanceAmount()).isZero();
    }

    @Test
    void quote_cash_hasNoDepositAndNoDueDate() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 2000, 5);
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(driverSubscriptionRepository.hasActiveSubscription(any(), any())).thenReturn(false);

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(1, null, PaymentMethod.CASH);

        PaymentPlanResponse plan = service.quote(trip.getId(), UUID.randomUUID(), req);

        assertThat(plan.depositAmount()).isZero();
        assertThat(plan.balanceAmount()).isEqualTo(2_000);
        assertThat(plan.depositDueAt()).isNull();
        assertThat(plan.paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void quote_momoFull_depositEqualsTotal() {
        TripRepository tripRepository = mock(TripRepository.class);
        DriverSubscriptionRepository driverSubscriptionRepository = mock(DriverSubscriptionRepository.class);
        Trip trip = publishedTrip(UUID.randomUUID(), 2000, 5);
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(driverSubscriptionRepository.hasActiveSubscription(any(), any())).thenReturn(false);

        BookingService service = newService(tripRepository, driverSubscriptionRepository);
        BookingQuoteRequest req = new BookingQuoteRequest(1, null, PaymentMethod.MOMO_FULL);

        PaymentPlanResponse plan = service.quote(trip.getId(), UUID.randomUUID(), req);

        assertThat(plan.depositAmount()).isEqualTo(2_000);
        assertThat(plan.balanceAmount()).isZero();
    }
}
