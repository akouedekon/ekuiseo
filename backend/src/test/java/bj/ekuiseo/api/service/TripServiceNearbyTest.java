package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.domain.enums.VehicleType;
import bj.ekuiseo.api.dto.trip.DriverSummary;
import bj.ekuiseo.api.dto.trip.NearbyTripResponse;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.mapper.TripMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * « Autour de moi » (TripService#nearby) : rayon par defaut et bornes, limite plafonnee,
 * ordre des lignes du depot conserve, distance en km au metre pres, point de montee
 * recopie, conducteur anonymise pour un appelant anonyme, aucune trace de recherche.
 */
class TripServiceNearbyTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripMapper tripMapper = mock(TripMapper.class);
    private final SearchEventService searchEventService = mock(SearchEventService.class);
    private TripService service;

    @BeforeEach
    void setUp() {
        service = new TripService(tripRepository, mock(TripStopRepository.class), mock(UserRepository.class),
                mock(VehicleRepository.class), tripMapper, mock(BookingService.class),
                mock(ApplicationEventPublisher.class), searchEventService, mock(RecurrenceService.class),
                mock(BookingRepository.class), mock(NotificationService.class));
    }

    private static TripRepository.NearbyTripRow row(UUID tripId, double distanceM, String label, double lat, double lng) {
        return new TripRepository.NearbyTripRow() {
            @Override public String getTripId() { return tripId.toString(); }
            @Override public double getDistanceM() { return distanceM; }
            @Override public String getBoardingLabel() { return label; }
            @Override public double getBoardingLat() { return lat; }
            @Override public double getBoardingLng() { return lng; }
        };
    }

    private static Trip trip(UUID id) {
        return Trip.builder().id(id).status(TripStatus.PUBLISHED).build();
    }

    private static TripResponse response(UUID id) {
        DriverSummary driver = new DriverSummary(UUID.randomUUID(), "Koffi", "Aholou", null, BigDecimal.valueOf(4.6), 12, true, bj.ekuiseo.api.domain.enums.TrustLevel.VERIFIED, 3);
        return new TripResponse(id, driver, null, TripType.QUOTIDIEN, "Abomey-Calavi", 6.4489, 2.3556,
                "Cotonou", 6.3703, 2.3912, Instant.parse("2026-09-11T06:30:00Z"), 3, 2, 500, true, null, null,
                TripStatus.PUBLISHED, null, Instant.parse("2026-09-10T08:00:00Z"), null, null, null, null, null);
    }

    @Test
    void defaultRadiusIsTenKilometres_andLimitIsCapped() {
        when(tripRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.nearby(null, 6.37, 2.39, null, null, 500)).isEmpty();

        verify(tripRepository).findNearby(eq(6.37), eq(2.39), eq(10_000.0), isNull(), any(Instant.class), eq(TripService.MAX_NEARBY_LIMIT));
        verify(tripRepository, never()).findAllById(any());
        verify(searchEventService, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void vehicleTypeIsPassedAsItsName_andRadiusInMetres() {
        when(tripRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt())).thenReturn(List.of());

        service.nearby(null, 6.37, 2.39, 2.5, VehicleType.MOTO, 5);

        verify(tripRepository).findNearby(eq(6.37), eq(2.39), eq(2_500.0), eq("MOTO"), any(Instant.class), eq(5));
    }

    @Test
    void radiusOutOfBounds_isRejected_beforeAnyQuery() {
        assertThatThrownBy(() -> service.nearby(null, 6.37, 2.39, 0.5, null, 30))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1 et 30 km");
        assertThatThrownBy(() -> service.nearby(null, 6.37, 2.39, 31.0, null, 30))
                .isInstanceOf(BadRequestException.class);
        verify(tripRepository, never()).findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt());
    }

    @Test
    void keepsRepositoryOrder_convertsDistance_andCopiesBoardingPoint() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        when(tripRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt()))
                .thenReturn(List.of(row(near, 812.4, "Carrefour Agla", 6.3801, 2.3712), row(far, 4_250.0, "Abomey-Calavi", 6.4489, 2.3556)));
        // findAllById ne garantit aucun ordre : le service doit reprendre celui des lignes.
        when(tripRepository.findAllById(any())).thenReturn(List.of(trip(far), trip(near)));
        when(tripMapper.toResponse(any(Trip.class))).thenAnswer(invocation -> response(invocation.<Trip>getArgument(0).getId()));

        List<NearbyTripResponse> result = service.nearby(UUID.randomUUID(), 6.37, 2.39, 10.0, null, 30);

        assertThat(result).extracting(r -> r.trip().id()).containsExactly(near, far);
        assertThat(result.get(0).distanceKm()).isEqualTo(0.812);
        assertThat(result.get(0).boardingLabel()).isEqualTo("Carrefour Agla");
        assertThat(result.get(0).boardingLat()).isEqualTo(6.3801);
        assertThat(result.get(0).boardingLng()).isEqualTo(2.3712);
        assertThat(result.get(1).distanceKm()).isEqualTo(4.25);
        // Appelant connecte : nom du conducteur entier.
        assertThat(result.get(0).trip().driver().lastName()).isEqualTo("Aholou");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(tripRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(near, far);
    }

    @Test
    void anonymousCaller_getsAnonymizedDriver_andMissingEntityIsSkipped() {
        UUID present = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        when(tripRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt()))
                .thenReturn(List.of(row(vanished, 100.0, "X", 6.0, 2.0), row(present, 200.0, "Y", 6.1, 2.1)));
        when(tripRepository.findAllById(any())).thenReturn(List.of(trip(present)));
        when(tripMapper.toResponse(any(Trip.class))).thenAnswer(invocation -> response(invocation.<Trip>getArgument(0).getId()));

        List<NearbyTripResponse> result = service.nearby(null, 6.37, 2.39, 10.0, null, 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).trip().id()).isEqualTo(present);
        assertThat(result.get(0).trip().driver().lastName()).isEqualTo("A.");
        assertThat(result.get(0).distanceKm()).isEqualTo(0.2);
    }
}
