package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LivePositionAck;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reception d une position (contrat C, V28) : participant autorise, fenetre, cadence de 2 s,
 * flags (zone, horloge, teleportation, precision), economie d ecritures, diffusion et
 * notifications d approche confiees a TripTrackingService.
 */
class LocationUpdateServiceTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripPositionRepository positionRepository = mock(TripPositionRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final LiveSessionRegistry registry = new LiveSessionRegistry();
    private final TripTrackingService tracking = mock(TripTrackingService.class);
    private final LocationUpdateService service = new LocationUpdateService(tripRepository, positionRepository,
            bookingRepository, userRepository, registry, tracking);

    private final Instant now = Instant.parse("2026-09-10T08:00:00Z");
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private Trip trip;
    private Booking booking;

    @BeforeEach
    void setUp() {
        User driver = User.builder().id(driverId).firstName("Rodrigue").build();
        User passenger = User.builder().id(passengerId).firstName("Awa").build();
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.PUBLISHED)
                .originLabel("Cotonou").originLat(6.37).originLng(2.39)
                .destLabel("Bohicon").destLat(7.18).destLng(2.07)
                .departureAt(now.plus(30, ChronoUnit.MINUTES))
                .liveSharingEnabled(true)
                .seatsTotal(3).seatsAvailable(2).pricePerSeat(3000).build();
        booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).status(BookingStatus.CONFIRMED).seats(1).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(eq(trip.getId()), eq(passengerId), anyList()))
                .thenReturn(List.of(booking));
        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(eq(trip.getId()), eq(strangerId), anyList()))
                .thenReturn(List.of());
        when(tracking.pickupPoints(trip)).thenReturn(List.of());
        when(tracking.intervalSeconds(eq(trip), any(), anyList(), any())).thenReturn(30);
    }

    /* --------------------------------------------------------------- droits */

    @Test
    void driver_isAccepted_broadcast_persisted_andApproachChecked() {
        LivePositionAck ack = service.record(trip.getId(), driverId, position(6.40, 2.35, now.minusSeconds(3)), now);

        assertThat(ack.accepted()).isTrue();
        assertThat(ack.flags()).isEmpty();
        assertThat(ack.intervalSeconds()).isEqualTo(30);

        ArgumentCaptor<TripPosition> saved = ArgumentCaptor.forClass(TripPosition.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(driverId);
        assertThat(saved.getValue().getRole()).isEqualTo(LiveRole.DRIVER);
        assertThat(saved.getValue().getFlags()).isNull();
        assertThat(saved.getValue().getRecordedAt()).isEqualTo(now.minusSeconds(3));
        assertThat(trip.getLastPositionAt()).isEqualTo(now.minusSeconds(3));

        ArgumentCaptor<LiveParticipant> broadcast = ArgumentCaptor.forClass(LiveParticipant.class);
        verify(tracking).broadcastPosition(eq(trip.getId()), broadcast.capture());
        assertThat(broadcast.getValue().role()).isEqualTo(LiveRole.DRIVER);
        assertThat(broadcast.getValue().firstName()).isEqualTo("Rodrigue");
        assertThat(broadcast.getValue().bookingId()).isNull();
        verify(tracking).notifyApproach(eq(trip), any(), anyList(), eq(now));
        assertThat(registry.driverPosition(trip.getId())).isPresent();
    }

    @Test
    void confirmedPassenger_isAccepted_evenWhenTheDriverDoesNotShare_withoutApproachNotifications() {
        trip.setLiveSharingEnabled(false);

        LivePositionAck ack = service.record(trip.getId(), passengerId, position(6.38, 2.40, now), now);

        assertThat(ack.accepted()).isTrue();
        ArgumentCaptor<LiveParticipant> broadcast = ArgumentCaptor.forClass(LiveParticipant.class);
        verify(tracking).broadcastPosition(eq(trip.getId()), broadcast.capture());
        assertThat(broadcast.getValue().role()).isEqualTo(LiveRole.PASSENGER);
        assertThat(broadcast.getValue().bookingId()).isEqualTo(booking.getId());
        assertThat(broadcast.getValue().firstName()).isEqualTo("Awa");
        verify(tracking, never()).notifyApproach(any(), any(), anyList(), any());
        assertThat(trip.getLastPositionAt()).isNull();
    }

    @Test
    void stranger_orUnconfirmedPassenger_is403() {
        assertThatThrownBy(() -> service.record(trip.getId(), strangerId, position(6.4, 2.35, now), now))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.record(trip.getId(), null, position(6.4, 2.35, now), now))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository).findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(trip.getId(), strangerId,
                List.of(BookingStatus.CONFIRMED));
        verify(positionRepository, never()).save(any());
        verify(tracking, never()).broadcastPosition(any(), any());
    }

    @Test
    void driver_isRefused_whenSharingIsOff_andUnknownTripIs404() {
        trip.setLiveSharingEnabled(false);
        assertThatThrownBy(() -> service.record(trip.getId(), driverId, position(6.4, 2.35, now), now))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("pas active");
        UUID unknown = UUID.randomUUID();
        when(tripRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record(unknown, driverId, position(6.4, 2.35, now), now))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void positions_areRefused_outsideTheWindow() {
        trip.setDepartureAt(now.plus(61, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> service.record(trip.getId(), driverId, position(6.4, 2.35, now), now))
                .isInstanceOf(BadRequestException.class);
        trip.setDepartureAt(now.plus(60, ChronoUnit.MINUTES));
        assertThat(service.record(trip.getId(), driverId, position(6.4, 2.35, now), now).accepted()).isTrue();

        trip.setStatus(TripStatus.ONGOING);
        trip.setDepartureAt(now.minus(2, ChronoUnit.HOURS));
        // 110 m en 10 s (40 km/h) : plausible.
        assertThat(service.record(trip.getId(), driverId, position(6.401, 2.35, now.plusSeconds(10)), now.plusSeconds(10)).accepted()).isTrue();
        for (TripStatus terminal : new TripStatus[] {TripStatus.COMPLETED, TripStatus.CANCELLED}) {
            trip.setStatus(terminal);
            assertThatThrownBy(() -> service.record(trip.getId(), driverId, position(6.4, 2.35, now), now.plusSeconds(20)))
                    .as("statut " + terminal).isInstanceOf(BadRequestException.class);
        }
    }

    /* -------------------------------------------------------------- cadence */

    @Test
    void secondPositionWithinTwoSeconds_is429_perParticipant() {
        service.record(trip.getId(), driverId, position(6.40, 2.35, now), now);
        assertThatThrownBy(() -> service.record(trip.getId(), driverId, position(6.40, 2.35, now.plusMillis(1_500)), now.plusMillis(1_500)))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(ex -> assertThat(((TooManyRequestsException) ex).getRetryAfterSeconds()).isEqualTo(1));
        // Un autre participant n est pas concerne par la cadence du premier.
        assertThat(service.record(trip.getId(), passengerId, position(6.38, 2.40, now.plusMillis(1_500)), now.plusMillis(1_500)).accepted()).isTrue();
        // Deux secondes plus tard : acceptee.
        assertThat(service.record(trip.getId(), driverId, position(6.40, 2.35, now.plusSeconds(2)), now.plusSeconds(2)).accepted()).isTrue();
    }

    /* ---------------------------------------------------------------- flags */

    @Test
    void outOfBenin_isFlagged_notBroadcast_butKeptForAnalysis() {
        LivePositionAck ack = service.record(trip.getId(), driverId, position(48.85, 2.35, now), now);

        assertThat(ack.accepted()).isFalse();
        assertThat(ack.flags()).containsExactly(LocationUpdateService.FLAG_OUT_OF_AREA);
        verify(tracking, never()).broadcastPosition(any(), any());
        ArgumentCaptor<TripPosition> saved = ArgumentCaptor.forClass(TripPosition.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue().getFlags()).isEqualTo("OUT_OF_AREA");
        assertThat(registry.driverPosition(trip.getId())).isEmpty();
        assertThat(trip.getLastPositionAt()).isNull();
    }

    @Test
    void clockSkew_isFlagged_andServerTimeKept_missingRecordedAtIsServerTimeWithoutFlag() {
        LivePositionAck skewed = service.record(trip.getId(), driverId, position(6.40, 2.35, now.minus(3, ChronoUnit.MINUTES)), now);
        assertThat(skewed.accepted()).isTrue();
        assertThat(skewed.flags()).containsExactly(LocationUpdateService.FLAG_CLOCK_SKEW);
        assertThat(registry.driverPosition(trip.getId()).orElseThrow().recordedAt()).isEqualTo(now);

        Instant later = now.plusSeconds(10);
        LivePositionAck ahead = service.record(trip.getId(), driverId, position(6.40, 2.35, later.plus(5, ChronoUnit.MINUTES)), later);
        assertThat(ahead.flags()).containsExactly(LocationUpdateService.FLAG_CLOCK_SKEW);

        Instant evenLater = now.plusSeconds(20);
        LivePositionAck missing = service.record(trip.getId(), driverId, position(6.40, 2.35, null), evenLater);
        assertThat(missing.flags()).isEmpty();
        assertThat(registry.driverPosition(trip.getId()).orElseThrow().recordedAt()).isEqualTo(evenLater);
    }

    @Test
    void teleportation_isFlagged_andNotBroadcast_thenHealsOnceSpeedIsPlausible() {
        service.record(trip.getId(), driverId, position(6.40, 2.35, now), now);
        // 90 km en 10 s : impossible.
        Instant t1 = now.plusSeconds(10);
        LivePositionAck jump = service.record(trip.getId(), driverId, position(7.18, 2.07, t1), t1);
        assertThat(jump.accepted()).isFalse();
        assertThat(jump.flags()).containsExactly(LocationUpdateService.FLAG_TELEPORT);
        assertThat(registry.driverPosition(trip.getId()).orElseThrow().lat()).isEqualTo(6.40);
        verify(tracking, times(1)).broadcastPosition(any(), any());

        // Une heure plus tard au meme endroit lointain : 90 km/h, plausible -> acceptee.
        Instant t2 = now.plus(1, ChronoUnit.HOURS);
        LivePositionAck settled = service.record(trip.getId(), driverId, position(7.18, 2.07, t2), t2);
        assertThat(settled.accepted()).isTrue();
        assertThat(settled.flags()).isEmpty();
        verify(tracking, times(2)).broadcastPosition(any(), any());
    }

    @Test
    void lowAccuracy_isFlagged_butStillBroadcast() {
        LivePositionAck ack = service.record(trip.getId(), driverId,
                new LivePositionRequest(6.40, 2.35, null, null, 800f, now), now);

        assertThat(ack.accepted()).isTrue();
        assertThat(ack.flags()).containsExactly(LocationUpdateService.FLAG_LOW_ACCURACY);
        ArgumentCaptor<LiveParticipant> broadcast = ArgumentCaptor.forClass(LiveParticipant.class);
        verify(tracking).broadcastPosition(eq(trip.getId()), broadcast.capture());
        assertThat(broadcast.getValue().flags()).containsExactly("LOW_ACCURACY");
    }

    /* ------------------------------------------------------------ ecritures */

    @Test
    void cleanPositions_areWrittenAtMostEvery30sOr200m_flaggedOnesAlways() {
        service.record(trip.getId(), driverId, position(6.4000, 2.3500, now), now);
        // 10 s et 50 m plus loin : memoire seulement.
        service.record(trip.getId(), driverId, position(6.4004, 2.3500, now.plusSeconds(10)), now.plusSeconds(10));
        verify(positionRepository, times(1)).save(any());
        assertThat(registry.driverPosition(trip.getId()).orElseThrow().lat()).isEqualTo(6.4004);
        // 20 s et 250 m depuis la derniere ecriture : ecrite (distance).
        service.record(trip.getId(), driverId, position(6.4023, 2.3500, now.plusSeconds(20)), now.plusSeconds(20));
        verify(positionRepository, times(2)).save(any());
        // 10 s plus tard sans bouger : rien ; 30 s plus tard sans bouger : ecrite (temps).
        service.record(trip.getId(), driverId, position(6.4023, 2.3500, now.plusSeconds(30)), now.plusSeconds(30));
        verify(positionRepository, times(2)).save(any());
        service.record(trip.getId(), driverId, position(6.4023, 2.3500, now.plusSeconds(50)), now.plusSeconds(50));
        verify(positionRepository, times(3)).save(any());
        // Une position signalee (precision) est toujours ecrite, meme juste apres.
        service.record(trip.getId(), driverId, new LivePositionRequest(6.4023, 2.3500, null, null, 900f, now.plusSeconds(52)), now.plusSeconds(52));
        verify(positionRepository, times(4)).save(any());
    }

    private static LivePositionRequest position(double lat, double lng, Instant recordedAt) {
        return new LivePositionRequest(lat, lng, null, null, null, recordedAt);
    }
}
