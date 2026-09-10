package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LiveStreamEvent;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.service.NotificationService;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Subscriber;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Lecture du suivi en direct (contrat C, V28) : visibilite par role, cadence recommandee,
 * notifications d approche une seule fois, flux SSE (instantane, diffusion filtree, fin,
 * battement) sans fuite d emetteur.
 */
class TripTrackingServiceTest {

    /** Emetteur qui enregistre ce qu on lui envoie au lieu de l ecrire sur une reponse HTTP. */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> events = new ArrayList<>();
        boolean completed;
        boolean failed;

        @Override
        public void send(SseEventBuilder builder) {
            if (failed) {
                throw new IllegalStateException("ferme");
            }
            StringBuilder sb = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                sb.append(part.getData());
            }
            events.add(sb.toString());
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
        }
    }

    private final LiveSessionRegistry registry = new LiveSessionRegistry();
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final TripStopRepository tripStopRepository = mock(TripStopRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final TripTrackingService service = new TripTrackingService(registry, tripRepository, bookingRepository,
            tripStopRepository, notificationService, objectMapper, Runnable::run,
            mock(PlatformTransactionManager.class), 30);

    private final Instant now = Instant.parse("2026-09-10T08:00:00Z");
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private final UUID otherPassengerId = UUID.randomUUID();
    private Trip trip;
    private Booking booking;
    private Booking otherBooking;
    private final User passenger = User.builder().id(passengerId).firstName("Awa").build();
    private final User otherPassenger = User.builder().id(otherPassengerId).firstName("Koffi").build();

    @BeforeEach
    void setUp() {
        User driver = User.builder().id(driverId).firstName("Rodrigue").build();
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).status(TripStatus.PUBLISHED)
                .originLabel("Cotonou").originLat(6.3703).originLng(2.3912)
                .destLabel("Bohicon").destLat(7.1786).destLng(2.0667)
                .departureAt(now.plus(30, ChronoUnit.MINUTES))
                .liveSharingEnabled(true)
                .seatsTotal(3).seatsAvailable(1).pricePerSeat(3000).build();
        booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).status(BookingStatus.CONFIRMED).seats(1).build();
        otherBooking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(otherPassenger).status(BookingStatus.CONFIRMED).seats(1).build();
        when(bookingRepository.findByTripIdAndStatusIn(trip.getId(), List.of(BookingStatus.CONFIRMED)))
                .thenReturn(List.of(booking, otherBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /* ---------------------------------------------------------------- droits */

    @Test
    void resolveViewer_driver_passenger_stranger() {
        assertThat(service.resolveViewer(trip, driverId)).isEqualTo(new Viewer(driverId, LiveRole.DRIVER, null));

        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(trip.getId(), passengerId,
                TripTrackingService.VIEWER_BOOKING_STATUSES)).thenReturn(List.of(booking));
        assertThat(service.resolveViewer(trip, passengerId)).isEqualTo(new Viewer(passengerId, LiveRole.PASSENGER, booking.getId()));

        UUID stranger = UUID.randomUUID();
        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(eq(trip.getId()), eq(stranger), anyList()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.resolveViewer(trip, stranger)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.resolveViewer(trip, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void visibleParticipants_dependOnTheRole() {
        LiveParticipant driver = participant(LiveRole.DRIVER, null, 6.40, 2.35);
        LiveParticipant me = participant(LiveRole.PASSENGER, booking.getId(), 6.38, 2.40);
        LiveParticipant other = participant(LiveRole.PASSENGER, otherBooking.getId(), 6.39, 2.41);
        registry.participant(trip.getId(), driverId, now.toEpochMilli()).setLast(driver);
        registry.participant(trip.getId(), passengerId, now.toEpochMilli()).setLast(me);
        registry.participant(trip.getId(), otherPassengerId, now.toEpochMilli()).setLast(other);

        assertThat(service.visibleParticipants(trip.getId(), new Viewer(driverId, LiveRole.DRIVER, null)))
                .containsExactlyInAnyOrder(driver, me, other);
        assertThat(service.visibleParticipants(trip.getId(), new Viewer(passengerId, LiveRole.PASSENGER, booking.getId())))
                .containsExactlyInAnyOrder(driver, me);
    }

    /* --------------------------------------------------------------- cadence */

    @Test
    void pickupPoints_useTheOriginOrThePickupStop() {
        UUID stopId = UUID.randomUUID();
        otherBooking.setPickupStopId(stopId);
        when(tripStopRepository.findByTripIdOrderByPosition(trip.getId())).thenReturn(List.of(
                TripStop.builder().id(stopId).trip(trip).position(1).label("Allada").lat(6.6650).lng(2.1514).build()));

        List<PickupPoint> points = service.pickupPoints(trip);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).lat()).isEqualTo(6.3703);
        assertThat(points.get(1).lat()).isEqualTo(6.6650);
        assertThat(points.get(1).booking()).isSameAs(otherBooking);
    }

    @Test
    void intervalSeconds_is30Before_15During_5NearAPickup() {
        List<PickupPoint> pickups = service.pickupPoints(trip);
        // Sans position du conducteur : l heure decide.
        assertThat(service.intervalSeconds(trip, null, pickups, now)).isEqualTo(30);
        assertThat(service.intervalSeconds(trip, null, pickups, trip.getDepartureAt())).isEqualTo(15);
        trip.setStatus(TripStatus.ONGOING);
        assertThat(service.intervalSeconds(trip, null, pickups, now)).isEqualTo(15);
        trip.setStatus(TripStatus.PUBLISHED);

        // A 50 km de Cotonou : cadence de l heure ; a 2 km : 5 s.
        LiveParticipant far = participant(LiveRole.DRIVER, null, 6.80, 2.39);
        assertThat(service.intervalSeconds(trip, far, pickups, now)).isEqualTo(30);
        LiveParticipant near = participant(LiveRole.DRIVER, null, 6.3883, 2.3912);
        assertThat(service.intervalSeconds(trip, near, pickups, now)).isEqualTo(5);
        // Sans passager confirme, jamais de cadence rapide.
        assertThat(service.intervalSeconds(trip, near, List.of(), now)).isEqualTo(30);
    }

    /* ------------------------------------------------------- notifications */

    @Test
    void approach_notifiesNearbyThenArrived_onceEach_pushOnly() {
        List<PickupPoint> pickups = service.pickupPoints(trip);

        // 5 km : rien.
        assertThat(service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.4153, 2.3912), pickups, now)).isZero();
        verify(notificationService, never()).notifyPushOnly(any(), any(), any());

        // 800 m : DRIVER_NEARBY aux deux passagers (meme point de prise en charge), une fois.
        Instant t1 = now.plusSeconds(60);
        assertThat(service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.3775, 2.3912), pickups, t1)).isEqualTo(2);
        assertThat(booking.getDriverNearbyNotifiedAt()).isEqualTo(t1);
        assertThat(otherBooking.getDriverNearbyNotifiedAt()).isEqualTo(t1);
        assertThat(service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.3770, 2.3912), pickups, t1.plusSeconds(5))).isZero();
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService, times(2)).notifyPushOnly(any(), eq(NotificationType.DRIVER_NEARBY), payload.capture());
        assertThat(payload.getValue()).containsEntry("driverFirstName", "Rodrigue").containsEntry("forPassenger", true)
                .containsKey("bookingId").containsKey("tripId").containsKey("distanceM");
        verify(notificationService, never()).notify(any(), any(), any());
        verify(notificationService, never()).notifyCritical(any(), any(), any());

        // 100 m : DRIVER_ARRIVED, une fois ; plus rien ensuite meme en restant sur place.
        Instant t2 = now.plusSeconds(180);
        assertThat(service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.3712, 2.3912), pickups, t2)).isEqualTo(2);
        assertThat(booking.getDriverArrivedNotifiedAt()).isEqualTo(t2);
        verify(notificationService).notifyPushOnly(eq(passenger), eq(NotificationType.DRIVER_ARRIVED), any());
        verify(notificationService).notifyPushOnly(eq(otherPassenger), eq(NotificationType.DRIVER_ARRIVED), any());
        assertThat(service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.3705, 2.3912), pickups, t2.plusSeconds(30))).isZero();
        verify(notificationService, times(4)).notifyPushOnly(any(), any(), any());
    }

    @Test
    void approach_arrivedDirectly_setsBothTimestamps_andSendsOnlyArrived() {
        List<PickupPoint> pickups = service.pickupPoints(trip);
        service.notifyApproach(trip, participant(LiveRole.DRIVER, null, 6.3708, 2.3912), pickups, now);

        assertThat(booking.getDriverNearbyNotifiedAt()).isEqualTo(now);
        assertThat(booking.getDriverArrivedNotifiedAt()).isEqualTo(now);
        verify(notificationService, times(2)).notifyPushOnly(any(), eq(NotificationType.DRIVER_ARRIVED), any());
        verify(notificationService, never()).notifyPushOnly(any(), eq(NotificationType.DRIVER_NEARBY), any());
    }

    /* ------------------------------------------------------------- flux SSE */

    @Test
    void openStream_sendsTheSnapshot_filteredByRole_andReleasesOnCompletion() {
        registry.participant(trip.getId(), driverId, now.toEpochMilli()).setLast(participant(LiveRole.DRIVER, null, 6.40, 2.35));
        registry.participant(trip.getId(), otherPassengerId, now.toEpochMilli())
                .setLast(participant(LiveRole.PASSENGER, otherBooking.getId(), 6.39, 2.41));

        SseEmitter emitter = service.openStream(trip, new Viewer(passengerId, LiveRole.PASSENGER, booking.getId()), now);

        assertThat(emitter.getTimeout()).isEqualTo(30L * 60_000);
        assertThat(registry.subscriberCount(trip.getId())).isEqualTo(1);
        // L instantane est mis en tampon par l emetteur jusqu a l engagement de la reponse : on le relit.
        Subscriber subscriber = registry.subscribers(trip.getId()).iterator().next();
        assertThat(subscriber.viewer().role()).isEqualTo(LiveRole.PASSENGER);
    }

    @Test
    void broadcastPosition_reachesOnlyThoseAllowedToSee_andDropsDeadEmitters() {
        RecordingEmitter driverStream = new RecordingEmitter();
        RecordingEmitter passengerStream = new RecordingEmitter();
        RecordingEmitter deadStream = new RecordingEmitter();
        deadStream.failed = true;
        registry.subscribe(trip.getId(), driverStream, new Viewer(driverId, LiveRole.DRIVER, null), now.toEpochMilli());
        registry.subscribe(trip.getId(), passengerStream, new Viewer(passengerId, LiveRole.PASSENGER, booking.getId()), now.toEpochMilli());
        registry.subscribe(trip.getId(), deadStream, new Viewer(otherPassengerId, LiveRole.PASSENGER, otherBooking.getId()), now.toEpochMilli());

        service.broadcastPosition(trip.getId(), participant(LiveRole.PASSENGER, otherBooking.getId(), 6.39, 2.41));
        service.broadcastPosition(trip.getId(), participant(LiveRole.DRIVER, null, 6.40, 2.35));

        assertThat(driverStream.events).hasSize(2);
        assertThat(driverStream.events.get(0)).startsWith("event:position").contains("\"type\":\"position\"").contains("\"role\":\"PASSENGER\"");
        assertThat(driverStream.events.get(1)).contains("\"role\":\"DRIVER\"").contains("\"firstName\":\"Rodrigue\"");
        // Le passager ne recoit pas la position de l autre passager.
        assertThat(passengerStream.events).hasSize(1);
        assertThat(passengerStream.events.get(0)).contains("\"role\":\"DRIVER\"");
        // Un client parti est ferme et ne fait pas echouer les autres.
        assertThat(deadStream.completed).isTrue();
    }

    @Test
    void status_andEnd_reachEveryone_thenTheSessionIsForgotten() {
        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        registry.subscribe(trip.getId(), a, new Viewer(driverId, LiveRole.DRIVER, null), now.toEpochMilli());
        registry.subscribe(trip.getId(), b, new Viewer(passengerId, LiveRole.PASSENGER, booking.getId()), now.toEpochMilli());

        service.broadcastStatus(trip, now);
        assertThat(a.events).hasSize(1);
        assertThat(a.events.get(0)).startsWith("event:status").contains("\"sharingEnabled\":true").contains("\"intervalSeconds\":30");

        service.end(trip.getId(), LiveStreamEvent.EndReason.SHARING_DISABLED);
        assertThat(a.events.get(1)).startsWith("event:end").contains("\"reason\":\"SHARING_DISABLED\"");
        assertThat(b.events).hasSize(2);
        assertThat(a.completed).isTrue();
        assertThat(b.completed).isTrue();
        assertThat(registry.hasSession(trip.getId())).isFalse();
    }

    @Test
    void heartbeat_pingsLiveTrips_andEndsCompletedOrCancelledOnes() {
        Trip cancelled = Trip.builder().id(UUID.randomUUID()).driver(trip.getDriver()).status(TripStatus.CANCELLED)
                .departureAt(now).originLat(6.37).originLng(2.39).destLat(7.18).destLng(2.07).build();
        Trip completed = Trip.builder().id(UUID.randomUUID()).driver(trip.getDriver()).status(TripStatus.COMPLETED)
                .departureAt(now).originLat(6.37).originLng(2.39).destLat(7.18).destLng(2.07).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));
        when(tripRepository.findById(completed.getId())).thenReturn(Optional.of(completed));
        RecordingEmitter live = new RecordingEmitter();
        RecordingEmitter dead = new RecordingEmitter();
        dead.failed = true;
        RecordingEmitter onCancelled = new RecordingEmitter();
        RecordingEmitter onCompleted = new RecordingEmitter();
        registry.subscribe(trip.getId(), live, new Viewer(driverId, LiveRole.DRIVER, null), now.toEpochMilli());
        registry.subscribe(trip.getId(), dead, new Viewer(passengerId, LiveRole.PASSENGER, booking.getId()), now.toEpochMilli());
        registry.subscribe(cancelled.getId(), onCancelled, new Viewer(driverId, LiveRole.DRIVER, null), now.toEpochMilli());
        registry.subscribe(completed.getId(), onCompleted, new Viewer(driverId, LiveRole.DRIVER, null), now.toEpochMilli());

        int pinged = service.heartbeat(now);

        assertThat(pinged).isEqualTo(1);
        assertThat(live.events).containsExactly("event:ping\ndata:" + now.getEpochSecond() + "\n\n");
        assertThat(registry.subscriberCount(trip.getId())).isEqualTo(1);
        assertThat(onCancelled.events.get(0)).contains("TRIP_CANCELLED");
        assertThat(onCompleted.events.get(0)).contains("TRIP_COMPLETED");
        assertThat(registry.tripsWithSubscribers()).isEqualTo(Set.of(trip.getId()));
        verify(tripRepository, never()).save(any());
    }

    private LiveParticipant participant(LiveRole role, UUID bookingId, double lat, double lng) {
        return new LiveParticipant(role, bookingId, role == LiveRole.DRIVER ? "Rodrigue" : "Awa", lat, lng, 300f, 50f, 8f, now, List.of());
    }
}
