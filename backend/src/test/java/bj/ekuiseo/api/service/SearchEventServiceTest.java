package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.domain.SearchEvent;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import bj.ekuiseo.api.repository.SearchEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie la construction d'une trace de recherche (search_events, migration V9) :
 * rattachement de l'origine et de la destination a la ville geo_places la plus
 * proche, bornage des libelles, valeurs par defaut sures, et surtout qu'une trace
 * en echec ne remonte jamais d'exception (elle s'ecrit hors du fil de la recherche).
 */
class SearchEventServiceTest {

    private static final double COTONOU_LAT = 6.3703;
    private static final double COTONOU_LNG = 2.3912;
    private static final double PARAKOU_LAT = 9.3372;
    private static final double PARAKOU_LNG = 2.6303;

    private SearchEventRepository searchEventRepository;
    private GeoPlaceRepository geoPlaceRepository;
    private SearchEventService service;

    @BeforeEach
    void setUp() {
        searchEventRepository = mock(SearchEventRepository.class);
        geoPlaceRepository = mock(GeoPlaceRepository.class);
        service = new SearchEventService(searchEventRepository, geoPlaceRepository);
    }

    @Test
    void buildEvent_resolvesNearestCityForOriginAndDestination() {
        GeoPlace cotonou = GeoPlace.builder().id(UUID.randomUUID()).name("Cotonou").build();
        GeoPlace parakou = GeoPlace.builder().id(UUID.randomUUID()).name("Parakou").build();
        when(geoPlaceRepository.findNearestCity(eq(COTONOU_LAT), eq(COTONOU_LNG), anyDouble())).thenReturn(Optional.of(cotonou));
        when(geoPlaceRepository.findNearestCity(eq(PARAKOU_LAT), eq(PARAKOU_LNG), anyDouble())).thenReturn(Optional.of(parakou));
        UUID userId = UUID.randomUUID();

        SearchEvent event = service.buildEvent(userId, request("Cotonou, gare Jonquet", "Parakou"), 3);

        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getOriginPlaceId()).isEqualTo(cotonou.getId());
        assertThat(event.getDestPlaceId()).isEqualTo(parakou.getId());
        assertThat(event.getOriginLabel()).isEqualTo("Cotonou, gare Jonquet");
        assertThat(event.getResultCount()).isEqualTo(3);
        assertThat(event.getTripType()).isEqualTo(TripType.INTERURBAIN);
        assertThat(event.getRequestedDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(event.getRadiusKm()).isEqualTo(15.0);
    }

    @Test
    void buildEvent_leavesPlaceNullWhenNoCityIsClose_andToleratesAnonymousBlankLabels() {
        when(geoPlaceRepository.findNearestCity(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());

        SearchEvent event = service.buildEvent(null, request("   ", null), 0);

        assertThat(event.getUserId()).isNull();
        assertThat(event.getOriginPlaceId()).isNull();
        assertThat(event.getDestPlaceId()).isNull();
        assertThat(event.getOriginLabel()).isNull();
        assertThat(event.getDestLabel()).isNull();
        assertThat(event.getResultCount()).isZero();
    }

    @Test
    void buildEvent_truncatesOverlongLabelsAndClampsSeats() {
        when(geoPlaceRepository.findNearestCity(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        String longLabel = "x".repeat(400);
        SearchEventService.SearchRequest req = new SearchEventService.SearchRequest(longLabel, COTONOU_LAT, COTONOU_LNG,
                "Parakou", PARAKOU_LAT, PARAKOU_LNG, null, 0, 5.0, null);

        SearchEvent event = service.buildEvent(null, req, 1);

        assertThat(event.getOriginLabel()).hasSize(255);
        assertThat(event.getSeats()).isEqualTo(1);
        assertThat(event.getTripType()).isNull();
        assertThat(event.getRequestedDate()).isNull();
    }

    @Test
    void record_savesTheEvent() {
        when(geoPlaceRepository.findNearestCity(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());

        service.record(null, request("Cotonou", "Parakou"), 2);

        ArgumentCaptor<SearchEvent> saved = ArgumentCaptor.forClass(SearchEvent.class);
        verify(searchEventRepository).save(saved.capture());
        assertThat(saved.getValue().getResultCount()).isEqualTo(2);
    }

    @Test
    void record_neverThrows_evenWhenTheDatabaseFails() {
        when(geoPlaceRepository.findNearestCity(anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> service.record(null, request("Cotonou", "Parakou"), 2)).doesNotThrowAnyException();
        verify(searchEventRepository, never()).save(any());
    }

    private static SearchEventService.SearchRequest request(String originLabel, String destLabel) {
        return new SearchEventService.SearchRequest(originLabel, COTONOU_LAT, COTONOU_LNG,
                destLabel, PARAKOU_LAT, PARAKOU_LNG, LocalDate.of(2026, 9, 10), 2, 15.0, TripType.INTERURBAIN);
    }
}
