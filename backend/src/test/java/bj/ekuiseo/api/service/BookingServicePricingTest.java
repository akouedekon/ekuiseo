package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
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
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tarif par troncon : une reservation qui descend a un arret intermediaire est
 * facturee au prix de cet arret (TripStop#priceFromOrigin), pas au prix du trajet
 * complet. Le devis (quote) et la reservation partagent le meme calcul.
 */
class BookingServicePricingTest {

    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private final UUID stopId = UUID.randomUUID();
    private BookingService service;

    @BeforeEach
    void setUp() {
        Trip trip = Trip.builder()
                .id(tripId)
                .driver(User.builder().id(driverId).build())
                .status(TripStatus.PUBLISHED)
                .pricePerSeat(3500)
                .seatsTotal(3)
                .seatsAvailable(3)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        TripStop stop = TripStop.builder().id(stopId).trip(trip).label("Allada").priceFromOrigin(1500).position(1).build();

        TripRepository tripRepository = mock(TripRepository.class);
        TripStopRepository tripStopRepository = mock(TripStopRepository.class);
        DriverSubscriptionRepository subscriptions = mock(DriverSubscriptionRepository.class);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(tripStopRepository.findByTripIdOrderByPosition(tripId)).thenReturn(List.of(stop));
        when(subscriptions.hasActiveSubscription(any(), any())).thenReturn(false);

        service = new BookingService(mock(BookingRepository.class), tripRepository, tripStopRepository,
                mock(UserRepository.class), subscriptions, mock(MessageRepository.class), mock(BookingMapper.class),
                new CancellationPolicy(), new DriverCancellationPolicy(), mock(NotificationService.class),
                mock(PaymentService.class), mock(AuditService.class), new FeePolicy(0.08, 5, 1000), 20);
    }

    @Test
    void quote_usesStopPrice_whenDroppingOffAtIntermediateStop() {
        PaymentPlanResponse plan = service.quote(tripId, passengerId,
                new BookingQuoteRequest(2, stopId, PaymentMethod.MOMO_DEPOSIT));
        assertThat(plan.totalAmount()).isEqualTo(3000);   // 2 x 1 500, pas 2 x 3 500
        assertThat(plan.serviceFee()).isEqualTo(240);     // 8 % de 3 000
        assertThat(plan.depositAmount()).isEqualTo(1000); // plancher
        assertThat(plan.balanceAmount()).isEqualTo(2000);
    }

    @Test
    void quote_usesTripPrice_withoutStop() {
        PaymentPlanResponse plan = service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, null, PaymentMethod.MOMO_FULL));
        assertThat(plan.totalAmount()).isEqualTo(3500);
        assertThat(plan.depositAmount()).isEqualTo(3500);
        assertThat(plan.balanceAmount()).isEqualTo(0);
    }

    @Test
    void quote_rejectsStopOfAnotherTrip() {
        assertThatThrownBy(() -> service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, UUID.randomUUID(), PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class);
    }
}
