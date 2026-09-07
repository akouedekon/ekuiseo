package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constats F526/F527/F523 : une requete, un evenement apres commit, une notification par
 * alerte avec lien. Phase 3 (F532/F533) : payload lisible, une seule notification par
 * (alerte, trajet ou navette).
 */
class SearchAlertMatchServiceTest {

    private final SearchAlertRepository alertRepository = mock(SearchAlertRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final SearchAlertMatchService service = new SearchAlertMatchService(alertRepository, tripRepository,
            notificationService, mock(org.springframework.transaction.PlatformTransactionManager.class));

    private final User driver = User.builder().id(UUID.randomUUID()).build();
    private final User passenger = User.builder().id(UUID.randomUUID()).build();
    private final Instant departure = Instant.parse("2026-09-12T06:30:00Z");
    private final Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).tripType(TripType.INTERURBAIN)
            .originLabel("Cotonou").destLabel("Bohicon").departureAt(departure).seatsAvailable(3).pricePerSeat(2500)
            .status(TripStatus.PUBLISHED).build();

    @BeforeEach
    void setUp() {
        when(alertRepository.insertMatch(any(), any())).thenReturn(1);
    }

    @Test
    void notifyMatchingAlerts_runsOneQuery_andNotifiesEachAlert_withAReadablePayload() {
        SearchAlert alert = SearchAlert.builder().id(UUID.randomUUID()).user(passenger).build();
        when(alertRepository.findMatching(eq(trip.getId()), eq(driver.getId()), eq(LocalDate.of(2026, 9, 12)), eq(3), eq("INTERURBAIN")))
                .thenReturn(List.of(alert));

        int matched = service.notifyMatchingAlerts(trip);

        assertThat(matched).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(passenger), eq(NotificationType.SEARCH_ALERT_MATCH), payload.capture());
        // Constat F532 : axe, date, prix et places lisibles cote front.
        assertThat(payload.getValue()).containsEntry("tripId", trip.getId().toString())
                .containsEntry("alertId", alert.getId().toString())
                .containsEntry("originLabel", "Cotonou")
                .containsEntry("destLabel", "Bohicon")
                .containsEntry("departureAt", departure.toString())
                .containsEntry("pricePerSeat", 2500L)
                .containsEntry("pricePerSeatFcfa", 2500L)
                .containsEntry("seatsAvailable", 3)
                .containsEntry("route", "Cotonou -> Bohicon");
        // Trajet isole : la cle de dedoublonnage est le trajet lui-meme.
        verify(alertRepository).insertMatch(alert.getId(), trip.getId());
        verify(tripRepository, never()).matchesAlertGeography(any(), any(Double.class), any(Double.class), any(Double.class), any(Double.class), any(Double.class));
        // Le jour du depart est celui du Benin.
        assertThat(departure.atZone(Tz.BENIN).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12));
    }

    /** Constat F533 : les occurrences d une navette partagent la cle du modele ; une alerte deja prevenue ne l est plus. */
    @Test
    void notifyMatchingAlerts_notifiesOncePerAlertAndTemplate() {
        UUID templateId = UUID.randomUUID();
        Trip occurrence = Trip.builder().id(UUID.randomUUID()).driver(driver).tripType(TripType.QUOTIDIEN)
                .originLabel("Calavi").destLabel("Cotonou").departureAt(departure).seatsAvailable(2).pricePerSeat(500)
                .status(TripStatus.PUBLISHED).parentTripId(templateId).build();
        SearchAlert alert = SearchAlert.builder().id(UUID.randomUUID()).user(passenger).build();
        when(alertRepository.findMatching(eq(occurrence.getId()), any(), any(), any(Integer.class), any())).thenReturn(List.of(alert));
        when(alertRepository.insertMatch(alert.getId(), templateId)).thenReturn(1).thenReturn(0);

        assertThat(service.notifyMatchingAlerts(occurrence)).isEqualTo(1);
        assertThat(service.notifyMatchingAlerts(occurrence)).as("deuxieme occurrence de la meme navette").isZero();

        verify(notificationService).notify(eq(passenger), eq(NotificationType.SEARCH_ALERT_MATCH), any());
    }

    @Test
    void onTripPublished_reloadsTheTrip_andIgnoresNonPublishedOnes() {
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(alertRepository.findMatching(any(), any(), any(), any(Integer.class), any())).thenReturn(List.of());

        service.onTripPublished(new TripPublishedEvent(trip.getId()));
        verify(alertRepository).findMatching(eq(trip.getId()), any(), any(), any(Integer.class), any());

        trip.setStatus(TripStatus.CANCELLED);
        service.onTripPublished(new TripPublishedEvent(trip.getId()));
        verify(alertRepository).findMatching(any(), any(), any(), any(Integer.class), any()); // toujours un seul appel
    }

    @Test
    void onTripPublished_neverPropagatesAnError() {
        when(tripRepository.findById(trip.getId())).thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> service.onTripPublished(new TripPublishedEvent(trip.getId()))).doesNotThrowAnyException();
    }
}
