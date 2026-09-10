package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.LiveStreamEvent;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.service.live.LiveSessionRegistry;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Viewer;
import bj.ekuiseo.api.service.live.TripTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/** Suivi en direct (V23/V28) : droits, jeton public, fraicheur, expiration du lien, et effets du partage sur les flux. */
class TripLiveServiceTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripPositionRepository positionRepository = mock(TripPositionRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final LiveSessionRegistry registry = new LiveSessionRegistry();
    private final TripTrackingService tracking = mock(TripTrackingService.class);
    private final TripLiveService service = new TripLiveService(tripRepository, positionRepository, auditService, registry, tracking);

    private final Instant now = Instant.parse("2026-09-10T08:00:00Z");
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private Trip trip;

    @BeforeEach
    void setUp() {
        User driver = User.builder().id(driverId).firstName("Rodrigue").lastName("Ahouansou").build();
        Vehicle vehicle = Vehicle.builder().id(UUID.randomUUID()).owner(driver).brand("Toyota").model("Corolla").color("Grise").build();
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).vehicle(vehicle).status(TripStatus.PUBLISHED)
                .originLabel("Cotonou").originLat(6.37).originLng(2.39)
                .destLabel("Bohicon").destLat(7.18).destLng(2.07)
                .departureAt(now.plus(30, ChronoUnit.MINUTES))
                .seatsTotal(3).seatsAvailable(2).pricePerSeat(3000).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tracking.currentInterval(any(), any())).thenReturn(30);
        when(tracking.visibleParticipants(any(), any())).thenReturn(List.of());
    }

    /* ----------------------------------------------------------- activation */

    @Test
    void enable_generatesAnUrlSafeToken_once_andAudits_andBroadcastsStatus() {
        LiveSharingResponse first = service.setSharing(trip.getId(), driverId, true, now);

        assertThat(first.enabled()).isTrue();
        assertThat(first.shareToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(first.sharePath()).isEqualTo("/live/" + first.shareToken());
        assertThat(first.intervalSeconds()).isEqualTo(30);
        verify(auditService).log(driverId, "TRIP_LIVE_SHARING_ENABLED", "trip", trip.getId(), Map.of());
        verify(tracking).broadcastStatus(trip, now);

        // Couper puis reprendre garde le meme lien : un proche qui l a deja recu n est pas perdu.
        LiveSharingResponse off = service.setSharing(trip.getId(), driverId, false, now);
        assertThat(off.enabled()).isFalse();
        assertThat(off.shareToken()).isEqualTo(first.shareToken());
        verify(auditService).log(driverId, "TRIP_LIVE_SHARING_DISABLED", "trip", trip.getId(), Map.of());

        LiveSharingResponse again = service.setSharing(trip.getId(), driverId, true, now);
        assertThat(again.shareToken()).isEqualTo(first.shareToken());
    }

    @Test
    void disable_closesTheStreams_andForgetsTheDriverPosition() {
        trip.setLiveSharingEnabled(true);
        trip.setLiveShareToken("tok");
        registry.participant(trip.getId(), driverId, now.toEpochMilli()).setLast(participant(LiveRole.DRIVER, null));

        service.setSharing(trip.getId(), driverId, false, now);

        verify(tracking).end(trip.getId(), LiveStreamEvent.EndReason.SHARING_DISABLED);
        assertThat(registry.driverPosition(trip.getId())).isEmpty();
        // Deja coupe : rien ne se passe une seconde fois.
        service.setSharing(trip.getId(), driverId, false, now);
        verify(tracking, org.mockito.Mockito.times(1)).end(any(), any());
    }

    @Test
    void enable_isRefused_toAnyoneButTheDriver() {
        assertThatThrownBy(() -> service.setSharing(trip.getId(), passengerId, true, now))
                .isInstanceOf(ForbiddenException.class);
        verify(tripRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void enable_isRefused_onACompletedCancelledOrTemplateTrip() {
        for (TripStatus status : new TripStatus[] {TripStatus.COMPLETED, TripStatus.CANCELLED, TripStatus.TEMPLATE, TripStatus.DRAFT}) {
            trip.setStatus(status);
            assertThatThrownBy(() -> service.setSharing(trip.getId(), driverId, true, now))
                    .as("statut " + status)
                    .isInstanceOf(BadRequestException.class);
        }
        // Couper reste toujours possible.
        trip.setStatus(TripStatus.COMPLETED);
        trip.setLiveSharingEnabled(true);
        assertThat(service.setSharing(trip.getId(), driverId, false, now).enabled()).isFalse();
    }

    @Test
    void unknownTrip_is404() {
        UUID unknown = UUID.randomUUID();
        when(tripRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setSharing(unknown, driverId, true, now)).isInstanceOf(NotFoundException.class);
    }

    /* -------------------------------------------------------------- lecture */

    @Test
    void getLive_prefersTheRegistry_andFallsBackToTheDatabase() {
        trip.setLiveSharingEnabled(true);
        trip.setLiveShareToken("tok");
        Viewer viewer = new Viewer(driverId, LiveRole.DRIVER, null);
        when(tracking.resolveViewer(trip, driverId)).thenReturn(viewer);
        when(positionRepository.findFirstByTripIdAndRoleOrderByRecordedAtDesc(trip.getId(), LiveRole.DRIVER))
                .thenReturn(Optional.of(dbPosition(now.minusSeconds(42))));

        // Registre vide (apres un redemarrage) : la base fait foi.
        LivePositionResponse fromDb = service.getLive(trip.getId(), driverId, now);
        assertThat(fromDb.enabled()).isTrue();
        assertThat(fromDb.position().lat()).isEqualTo(6.40);
        assertThat(fromDb.staleSeconds()).isEqualTo(42L);
        assertThat(fromDb.shareToken()).isEqualTo("tok");
        assertThat(fromDb.intervalSeconds()).isEqualTo(30);
        assertThat(fromDb.serverTime()).isEqualTo(now);

        // Une position en memoire prime, meme sans lecture de la base.
        LiveParticipant live = participant(LiveRole.DRIVER, null);
        registry.participant(trip.getId(), driverId, now.toEpochMilli()).setLast(live);
        when(tracking.visibleParticipants(trip.getId(), viewer)).thenReturn(List.of(live));
        LivePositionResponse fromRegistry = service.getLive(trip.getId(), driverId, now);
        assertThat(fromRegistry.position().lat()).isEqualTo(live.lat());
        assertThat(fromRegistry.staleSeconds()).isEqualTo(5L);
        assertThat(fromRegistry.participants()).containsExactly(live);
    }

    @Test
    void getLive_hidesPositionAndToken_whenSharingIsOff() {
        when(tracking.resolveViewer(trip, passengerId)).thenReturn(new Viewer(passengerId, LiveRole.PASSENGER, UUID.randomUUID()));
        trip.setLiveShareToken("tok");

        LivePositionResponse res = service.getLive(trip.getId(), passengerId, now);

        assertThat(res.enabled()).isFalse();
        assertThat(res.position()).isNull();
        assertThat(res.staleSeconds()).isNull();
        assertThat(res.shareToken()).isNull();
        verify(positionRepository, never()).findFirstByTripIdAndRoleOrderByRecordedAtDesc(any(), any());
    }

    @Test
    void getLive_andStream_are403_forAStranger() {
        when(tracking.resolveViewer(trip, passengerId)).thenThrow(new ForbiddenException("reserve"));
        assertThatThrownBy(() -> service.getLive(trip.getId(), passengerId, now)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.stream(trip.getId(), passengerId, now)).isInstanceOf(ForbiddenException.class);
        verify(tracking, never()).openStream(any(), any(), any());
    }

    @Test
    void stream_opensAnEmitter_forAnAuthorisedViewer() {
        Viewer viewer = new Viewer(driverId, LiveRole.DRIVER, null);
        when(tracking.resolveViewer(trip, driverId)).thenReturn(viewer);
        SseEmitter emitter = new SseEmitter();
        when(tracking.openStream(trip, viewer, now)).thenReturn(emitter);

        assertThat(service.stream(trip.getId(), driverId, now)).isSameAs(emitter);
    }

    /* ------------------------------------------------------- lien public */

    @Test
    void getPublic_exposesOnlyWhatARelativeNeeds_andOnlyTheDriver() {
        trip.setLiveSharingEnabled(true);
        trip.setLiveShareToken("tok");
        when(tripRepository.findByLiveShareToken("tok")).thenReturn(Optional.of(trip));
        when(positionRepository.findFirstByTripIdAndRoleOrderByRecordedAtDesc(trip.getId(), LiveRole.DRIVER))
                .thenReturn(Optional.of(dbPosition(now.minusSeconds(100))));
        // Un passager qui partage n apparait jamais sur le lien public.
        registry.participant(trip.getId(), passengerId, now.toEpochMilli()).setLast(participant(LiveRole.PASSENGER, UUID.randomUUID()));

        PublicLiveResponse res = service.getPublic("tok", now);

        assertThat(res.driverFirstName()).isEqualTo("Rodrigue");
        assertThat(res.vehicle()).isEqualTo(new PublicLiveResponse.Vehicle("Toyota", "Corolla", "Grise"));
        assertThat(res.originLabel()).isEqualTo("Cotonou");
        assertThat(res.destLabel()).isEqualTo("Bohicon");
        assertThat(res.staleSeconds()).isEqualTo(100L);
        assertThat(res.position().lng()).isEqualTo(2.35);
    }

    @Test
    void getPublic_is404_whenUnknown_disabled_orLongFinished() {
        when(tripRepository.findByLiveShareToken(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublic("nope", now)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getPublic("", now)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getPublic(null, now)).isInstanceOf(NotFoundException.class);

        trip.setLiveShareToken("tok");
        when(tripRepository.findByLiveShareToken("tok")).thenReturn(Optional.of(trip));
        // Partage coupe par le conducteur : le lien est revoque.
        trip.setLiveSharingEnabled(false);
        assertThatThrownBy(() -> service.getPublic("tok", now)).isInstanceOf(NotFoundException.class);

        // Termine il y a moins de 6 h : repond encore. Plus de 6 h : expire.
        trip.setLiveSharingEnabled(true);
        trip.setStatus(TripStatus.COMPLETED);
        trip.setUpdatedAt(now.minus(5, ChronoUnit.HOURS));
        when(positionRepository.findFirstByTripIdAndRoleOrderByRecordedAtDesc(any(), eq(LiveRole.DRIVER))).thenReturn(Optional.empty());
        assertThat(service.getPublic("tok", now).tripStatus()).isEqualTo(TripStatus.COMPLETED);
        trip.setUpdatedAt(now.minus(7, ChronoUnit.HOURS));
        assertThatThrownBy(() -> service.getPublic("tok", now)).isInstanceOf(NotFoundException.class);
        trip.setStatus(TripStatus.CANCELLED);
        assertThatThrownBy(() -> service.getPublic("tok", now)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void newToken_isUnpredictable_andUrlSafe() {
        String a = service.newToken();
        String b = service.newToken();
        assertThat(a).isNotEqualTo(b).hasSize(43).doesNotContain("=", "+", "/");
    }

    private LiveParticipant participant(LiveRole role, UUID bookingId) {
        return new LiveParticipant(role, bookingId, role == LiveRole.DRIVER ? "Rodrigue" : "Awa", 6.45, 2.30, 300f, 50f, 8f,
                now.minusSeconds(5), List.of());
    }

    private TripPosition dbPosition(Instant recordedAt) {
        return TripPosition.builder().id(UUID.randomUUID()).trip(trip).userId(driverId).role(LiveRole.DRIVER)
                .lat(6.40).lng(2.35).heading(300f).speedKmh(50f).accuracyM(8f).recordedAt(recordedAt).build();
    }
}
