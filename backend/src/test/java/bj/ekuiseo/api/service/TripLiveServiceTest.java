package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Suivi en direct (V23) : droits, fenetre d envoi, jeton public, fraicheur et expiration du lien. */
class TripLiveServiceTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripPositionRepository positionRepository = mock(TripPositionRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TripLiveService service = new TripLiveService(tripRepository, positionRepository, bookingRepository, auditService);

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
    }

    /* ----------------------------------------------------------- activation */

    @Test
    void enable_generatesAnUrlSafeToken_once_andAudits() {
        LiveSharingResponse first = service.setSharing(trip.getId(), driverId, true);

        assertThat(first.enabled()).isTrue();
        assertThat(first.shareToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(first.sharePath()).isEqualTo("/live/" + first.shareToken());
        verify(auditService).log(driverId, "TRIP_LIVE_SHARING_ENABLED", "trip", trip.getId(), Map.of());

        // Couper puis reprendre garde le meme lien : un proche qui l a deja recu n est pas perdu.
        LiveSharingResponse off = service.setSharing(trip.getId(), driverId, false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.shareToken()).isEqualTo(first.shareToken());
        verify(auditService).log(driverId, "TRIP_LIVE_SHARING_DISABLED", "trip", trip.getId(), Map.of());

        LiveSharingResponse again = service.setSharing(trip.getId(), driverId, true);
        assertThat(again.shareToken()).isEqualTo(first.shareToken());
    }

    @Test
    void enable_isRefused_toAnyoneButTheDriver() {
        assertThatThrownBy(() -> service.setSharing(trip.getId(), passengerId, true))
                .isInstanceOf(ForbiddenException.class);
        verify(tripRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void enable_isRefused_onACompletedCancelledOrTemplateTrip() {
        for (TripStatus status : new TripStatus[] {TripStatus.COMPLETED, TripStatus.CANCELLED, TripStatus.TEMPLATE, TripStatus.DRAFT}) {
            trip.setStatus(status);
            assertThatThrownBy(() -> service.setSharing(trip.getId(), driverId, true))
                    .as("statut " + status)
                    .isInstanceOf(BadRequestException.class);
        }
        // Couper reste toujours possible.
        trip.setStatus(TripStatus.COMPLETED);
        trip.setLiveSharingEnabled(true);
        assertThat(service.setSharing(trip.getId(), driverId, false).enabled()).isFalse();
    }

    @Test
    void unknownTrip_is404() {
        UUID unknown = UUID.randomUUID();
        when(tripRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setSharing(unknown, driverId, true)).isInstanceOf(NotFoundException.class);
    }

    /* ------------------------------------------------------------ positions */

    @Test
    void recordPosition_savesIt_andUpdatesLastPositionAt() {
        trip.setLiveSharingEnabled(true);
        Instant recorded = now.minusSeconds(5);

        service.recordPosition(trip.getId(), driverId, new LivePositionRequest(6.40, 2.35, 310f, 62f, 12f, recorded), now);

        ArgumentCaptor<TripPosition> saved = ArgumentCaptor.forClass(TripPosition.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue().getTrip()).isSameAs(trip);
        assertThat(saved.getValue().getLat()).isEqualTo(6.40);
        assertThat(saved.getValue().getLng()).isEqualTo(2.35);
        assertThat(saved.getValue().getHeading()).isEqualTo(310f);
        assertThat(saved.getValue().getSpeedKmh()).isEqualTo(62f);
        assertThat(saved.getValue().getRecordedAt()).isEqualTo(recorded);
        assertThat(trip.getLastPositionAt()).isEqualTo(recorded);
    }

    @Test
    void recordPosition_clampsAClockAhead_andDefaultsToNow() {
        trip.setLiveSharingEnabled(true);

        service.recordPosition(trip.getId(), driverId, new LivePositionRequest(6.40, 2.35, null, null, null, now.plus(10, ChronoUnit.MINUTES)), now);
        service.recordPosition(trip.getId(), driverId, new LivePositionRequest(6.41, 2.36, null, null, null, null), now);

        ArgumentCaptor<TripPosition> saved = ArgumentCaptor.forClass(TripPosition.class);
        verify(positionRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TripPosition::getRecordedAt).containsExactly(now, now);
    }

    @Test
    void recordPosition_isRefused_whenSharingIsOff() {
        assertThatThrownBy(() -> service.recordPosition(trip.getId(), driverId, position(), now))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pas active");
        verify(positionRepository, never()).save(any());
    }

    @Test
    void recordPosition_isRefused_outsideTheWindow() {
        trip.setLiveSharingEnabled(true);

        // Plus d une heure avant le depart.
        trip.setDepartureAt(now.plus(61, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> service.recordPosition(trip.getId(), driverId, position(), now))
                .isInstanceOf(BadRequestException.class);

        // Exactement une heure avant : accepte.
        trip.setDepartureAt(now.plus(60, ChronoUnit.MINUTES));
        service.recordPosition(trip.getId(), driverId, position(), now);

        // Trajet en cours : accepte. Termine ou annule : refuse.
        trip.setStatus(TripStatus.ONGOING);
        trip.setDepartureAt(now.minus(2, ChronoUnit.HOURS));
        service.recordPosition(trip.getId(), driverId, position(), now);
        trip.setStatus(TripStatus.COMPLETED);
        assertThatThrownBy(() -> service.recordPosition(trip.getId(), driverId, position(), now))
                .isInstanceOf(BadRequestException.class);
        trip.setStatus(TripStatus.CANCELLED);
        assertThatThrownBy(() -> service.recordPosition(trip.getId(), driverId, position(), now))
                .isInstanceOf(BadRequestException.class);
        verify(positionRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void recordPosition_isRefused_toAPassenger() {
        trip.setLiveSharingEnabled(true);
        assertThatThrownBy(() -> service.recordPosition(trip.getId(), passengerId, position(), now))
                .isInstanceOf(ForbiddenException.class);
    }

    /* -------------------------------------------------------------- lecture */

    @Test
    void getLive_forTheDriver_returnsLastPositionAndStaleness() {
        trip.setLiveSharingEnabled(true);
        trip.setLiveShareToken("tok");
        when(positionRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId()))
                .thenReturn(Optional.of(position(now.minusSeconds(42))));

        LivePositionResponse res = service.getLive(trip.getId(), driverId, now);

        assertThat(res.enabled()).isTrue();
        assertThat(res.position()).isNotNull();
        assertThat(res.position().lat()).isEqualTo(6.40);
        assertThat(res.staleSeconds()).isEqualTo(42L);
        assertThat(res.tripStatus()).isEqualTo(TripStatus.PUBLISHED);
        assertThat(res.departureAt()).isEqualTo(trip.getDepartureAt());
        assertThat(res.shareToken()).isEqualTo("tok");
        verify(bookingRepository, never()).existsByTripIdAndPassengerIdAndStatusIn(any(), any(), any());
    }

    @Test
    void getLive_forAConfirmedPassenger_isAllowed_andHidesTokenWhenOff() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(trip.getId(), passengerId, TripLiveService.VIEWER_BOOKING_STATUSES))
                .thenReturn(true);
        trip.setLiveShareToken("tok");

        LivePositionResponse res = service.getLive(trip.getId(), passengerId, now);

        assertThat(res.enabled()).isFalse();
        assertThat(res.position()).isNull();
        assertThat(res.staleSeconds()).isNull();
        assertThat(res.shareToken()).isNull();
        verify(positionRepository, never()).findFirstByTripIdOrderByRecordedAtDesc(any());
    }

    @Test
    void getLive_forAStranger_is403() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.getLive(trip.getId(), passengerId, now)).isInstanceOf(ForbiddenException.class);
    }

    /* ------------------------------------------------------- lien public */

    @Test
    void getPublic_exposesOnlyWhatARelativeNeeds() {
        trip.setLiveSharingEnabled(true);
        trip.setLiveShareToken("tok");
        when(tripRepository.findByLiveShareToken("tok")).thenReturn(Optional.of(trip));
        when(positionRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId()))
                .thenReturn(Optional.of(position(now.minusSeconds(100))));

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
        when(positionRepository.findFirstByTripIdOrderByRecordedAtDesc(any())).thenReturn(Optional.empty());
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

    private static LivePositionRequest position() {
        return new LivePositionRequest(6.40, 2.35, null, null, null, null);
    }

    private TripPosition position(Instant recordedAt) {
        return TripPosition.builder().id(UUID.randomUUID()).trip(trip).lat(6.40).lng(2.35).heading(300f)
                .speedKmh(50f).accuracyM(8f).recordedAt(recordedAt).build();
    }
}
