package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.FeePolicy;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.booking.BookingQuoteRequest;
import bj.ekuiseo.api.dto.booking.CreateBookingRequest;
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
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tarif par troncon (constat F122) : une reservation qui monte et/ou descend a un arret
 * intermediaire est facturee a la difference des prix depuis l origine
 * (TripStop#priceFromOrigin), pas au prix du trajet complet ; un arret d un autre trajet ou
 * une montee apres la descente sont refuses. Le devis (quote) et la reservation partagent
 * le meme calcul.
 */
class BookingServicePricingTest {

    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private final UUID stop1Id = UUID.randomUUID();
    private final UUID stop2Id = UUID.randomUUID();
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private BookingService service;

    @BeforeEach
    void setUp() {
        Trip trip = Trip.builder()
                .id(tripId)
                .driver(User.builder().id(driverId).identityVerified(true).build())
                .status(TripStatus.PUBLISHED)
                .pricePerSeat(3500)
                .seatsTotal(3)
                .seatsAvailable(3)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        TripStop stop1 = TripStop.builder().id(stop1Id).trip(trip).label("Allada").priceFromOrigin(1500).position(1).build();
        TripStop stop2 = TripStop.builder().id(stop2Id).trip(trip).label("Bohicon").priceFromOrigin(2500).position(2).build();

        TripStopRepository tripStopRepository = mock(TripStopRepository.class);
        DriverSubscriptionRepository subscriptions = mock(DriverSubscriptionRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(tripRepository.decrementSeatsIfAvailable(eq(tripId), anyInt())).thenReturn(1);
        when(tripStopRepository.findByTripIdOrderByPosition(tripId)).thenReturn(List.of(stop1, stop2));
        when(subscriptions.hasActiveSubscription(any(), any())).thenReturn(false);
        when(userRepository.findById(passengerId)).thenReturn(Optional.of(User.builder().id(passengerId).firstName("Jean").build()));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        service = new BookingService(bookingRepository, tripRepository, tripStopRepository,
                userRepository, subscriptions, mock(MessageRepository.class), mock(bj.ekuiseo.api.repository.ReviewRepository.class), mock(BookingMapper.class),
                new CancellationPolicy(), new DriverCancellationPolicy(), mock(NotificationService.class),
                mock(PaymentService.class), mock(AuditService.class), new FeePolicy(0.08, 5, 1000), new DriverApprovalPolicy(24), 20);
    }

    @Test
    void quote_usesStopPrice_whenDroppingOffAtIntermediateStop() {
        PaymentPlanResponse plan = service.quote(tripId, passengerId,
                new BookingQuoteRequest(2, null, stop1Id, PaymentMethod.MOMO_DEPOSIT));
        assertThat(plan.totalAmount()).isEqualTo(3000);   // 2 x 1 500, pas 2 x 3 500
        assertThat(plan.serviceFee()).isEqualTo(240);     // 8 % de 3 000
        assertThat(plan.depositAmount()).isEqualTo(1000); // plancher
        assertThat(plan.balanceAmount()).isEqualTo(2000);
    }

    @Test
    void quote_usesPriceDifference_betweenPickupAndDropoff() {
        // Montee a Allada (1 500 depuis l origine), descente a Bohicon (2 500) : 1 000 F la place.
        PaymentPlanResponse plan = service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, stop1Id, stop2Id, PaymentMethod.MOMO_FULL));
        assertThat(plan.totalAmount()).isEqualTo(1000);

        // Montee a Bohicon, descente a la destination : 3 500 - 2 500.
        PaymentPlanResponse toDestination = service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, stop2Id, null, PaymentMethod.MOMO_FULL));
        assertThat(toDestination.totalAmount()).isEqualTo(1000);
    }

    @Test
    void quote_usesTripPrice_withoutStop() {
        PaymentPlanResponse plan = service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, null, null, PaymentMethod.MOMO_FULL));
        assertThat(plan.totalAmount()).isEqualTo(3500);
        assertThat(plan.depositAmount()).isEqualTo(3500);
        assertThat(plan.balanceAmount()).isEqualTo(0);
    }

    @Test
    void quote_rejectsStopOfAnotherTrip_forPickupAndDropoff() {
        assertThatThrownBy(() -> service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, null, UUID.randomUUID(), PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Arret inconnu");
        assertThatThrownBy(() -> service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, UUID.randomUUID(), stop2Id, PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Arret inconnu");
    }

    @Test
    void quote_rejectsPickupAtOrAfterDropoff() {
        assertThatThrownBy(() -> service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, stop2Id, stop1Id, PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("preceder");
        assertThatThrownBy(() -> service.quote(tripId, passengerId,
                new BookingQuoteRequest(1, stop1Id, stop1Id, PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("preceder");
    }

    /** createBooking applique le meme prix de troncon et fige les deux arrets sur la reservation. */
    @Test
    void createBooking_storesStops_andSegmentPrice() {
        service.createBooking(tripId, passengerId, new CreateBookingRequest(2, stop1Id, stop2Id, PaymentMethod.MOMO_DEPOSIT));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualTo(2000); // 2 x (2 500 - 1 500)
        assertThat(saved.getValue().getPickupStopId()).isEqualTo(stop1Id);
        assertThat(saved.getValue().getDropoffStopId()).isEqualTo(stop2Id);
    }

    /** Un arret inconnu est refuse AVANT toute decrementation de places. */
    @Test
    void createBooking_rejectsUnknownStop_beforeTakingSeats() {
        assertThatThrownBy(() -> service.createBooking(tripId, passengerId,
                new CreateBookingRequest(1, UUID.randomUUID(), null, PaymentMethod.MOMO_DEPOSIT)))
                .isInstanceOf(BadRequestException.class);
        verify(tripRepository, never()).decrementSeatsIfAvailable(any(), anyInt());
        verify(bookingRepository, never()).save(any());
    }
}
