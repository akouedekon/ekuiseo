package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.SearchEventRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie le calcul des indicateurs de liquidite (GET /api/v1/admin/stats/liquidity)
 * a partir des projections brutes des depots : taux en pourcentage arrondis au
 * dixieme, denominateurs nuls sans division, fenetres de periode courante et
 * precedente, metrique nord extrapolee a 30 jours, et export CSV lisible dans un
 * tableur en francais.
 */
class AdminLiquidityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private SearchEventRepository searchEventRepository;
    private TripRepository tripRepository;
    private BookingRepository bookingRepository;
    private AdminLiquidityService service;

    @BeforeEach
    void setUp() {
        searchEventRepository = mock(SearchEventRepository.class);
        tripRepository = mock(TripRepository.class);
        bookingRepository = mock(BookingRepository.class);
        service = new AdminLiquidityService(searchEventRepository, tripRepository, bookingRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(searchEventRepository.getFunnelStats(any(), any())).thenReturn(funnel(0, 0, 0, 0));
        when(searchEventRepository.findShortageRoutes(any(), any(), anyInt())).thenReturn(List.of());
        when(tripRepository.getFillStatsByMode(any(), any(), anyList())).thenReturn(List.of());
        when(tripRepository.getFillStatsByRoute(any(), any(), anyList(), anyInt())).thenReturn(List.of());
        when(tripRepository.getFirstBookingDelayStats(any(), any(), anyList())).thenReturn(delay(null, 0));
        when(bookingRepository.getSeatsByWeek(any(), any(), anyList())).thenReturn(List.of());
    }

    @Test
    void compute_rejectsOutOfRangeDays() {
        assertThatThrownBy(() -> service.compute(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.compute(366)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void compute_usesCurrentAndPreviousWindowsOfSameLength() {
        service.compute(30);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(searchEventRepository, org.mockito.Mockito.times(2)).getFunnelStats(from.capture(), to.capture());

        Instant periodStart = NOW.minus(30, ChronoUnit.DAYS);
        assertThat(from.getAllValues()).containsExactly(periodStart, periodStart.minus(30, ChronoUnit.DAYS));
        assertThat(to.getAllValues()).containsExactly(NOW, periodStart);
    }

    @Test
    void compute_derivesRatesFromRawCountsAndRoundsToOneDecimal() {
        Instant periodStart = NOW.minus(7, ChronoUnit.DAYS);
        when(searchEventRepository.getFunnelStats(eq(periodStart), eq(NOW))).thenReturn(funnel(300, 200, 90, 30));
        when(tripRepository.getFillStatsByMode(eq(periodStart), eq(NOW), anyList())).thenReturn(List.of(
                mode("INTERURBAIN", 40, 160, 100, 6),
                mode("QUOTIDIEN", 20, 80, 60, 3)));
        when(tripRepository.getFirstBookingDelayStats(eq(periodStart), eq(NOW), anyList())).thenReturn(delay(5.26, 51));

        AdminLiquidityResponse.Headline current = service.compute(7).current();

        assertThat(current.searches()).isEqualTo(300);
        assertThat(current.searchSuccessRate()).isEqualTo(66.7);
        // Conversion calculee sur les recherches d'utilisateurs connectes (90), jamais sur le total.
        assertThat(current.searchToBookingRate()).isEqualTo(33.3);
        assertThat(current.trips()).isEqualTo(60);
        assertThat(current.seatsPublished()).isEqualTo(240);
        assertThat(current.seatsBooked()).isEqualTo(160);
        assertThat(current.fillRate()).isEqualTo(66.7);
        assertThat(current.orphanTrips()).isEqualTo(9);
        assertThat(current.orphanRate()).isEqualTo(15.0);
        assertThat(current.medianHoursToFirstBooking()).isEqualTo(5.3);
        assertThat(current.firstBookingSampleSize()).isEqualTo(51);
    }

    @Test
    void compute_reportsZeroRatesAndNullMedianWhenNothingHappened() {
        AdminLiquidityResponse response = service.compute(30);

        assertThat(response.current().searchSuccessRate()).isZero();
        assertThat(response.current().fillRate()).isZero();
        assertThat(response.current().orphanRate()).isZero();
        assertThat(response.current().medianHoursToFirstBooking()).isNull();
        assertThat(response.fillByMode()).isEmpty();
        assertThat(response.shortageRoutes()).isEmpty();
    }

    @Test
    void compute_extrapolatesNorthStarToMonthlyPace() {
        Instant periodStart = NOW.minus(7, ChronoUnit.DAYS);
        when(bookingRepository.sumSeatsBetween(eq(periodStart), eq(NOW), anyList())).thenReturn(280L);
        when(bookingRepository.sumSeatsBetween(eq(periodStart.minus(7, ChronoUnit.DAYS)), eq(periodStart), anyList()))
                .thenReturn(210L);

        AdminLiquidityResponse.NorthStar northStar = service.compute(7).northStar();

        assertThat(northStar.confirmedSeats()).isEqualTo(280);
        assertThat(northStar.previousConfirmedSeats()).isEqualTo(210);
        assertThat(northStar.monthlyPace()).isEqualTo(1200.0); // 280 places / 7 j * 30 j
        assertThat(northStar.monthlyTarget()).isEqualTo(2000);
        assertThat(northStar.progressPercent()).isEqualTo(60.0);
    }

    @Test
    void compute_mapsFillByModeShortageRoutesAndLastSearchedInstant() {
        Instant periodStart = NOW.minus(30, ChronoUnit.DAYS);
        when(tripRepository.getFillStatsByMode(eq(periodStart), eq(NOW), anyList()))
                .thenReturn(List.of(mode("QUOTIDIEN", 10, 40, 10, 5)));
        SearchEventRepository.ShortageRoute shortage = mock(SearchEventRepository.ShortageRoute.class);
        when(shortage.getOrigin()).thenReturn("Cotonou");
        when(shortage.getDestination()).thenReturn("Natitingou");
        when(shortage.getSearches()).thenReturn(12L);
        when(shortage.getWithoutResults()).thenReturn(9L);
        when(shortage.getLastSearchedEpoch()).thenReturn(1_756_900_000.0);
        when(searchEventRepository.findShortageRoutes(eq(periodStart), eq(NOW), anyInt())).thenReturn(List.of(shortage));

        AdminLiquidityResponse response = service.compute(30);

        assertThat(response.fillByMode()).hasSize(1);
        AdminLiquidityResponse.ModeFill quotidien = response.fillByMode().get(0);
        assertThat(quotidien.tripType()).isEqualTo(TripType.QUOTIDIEN);
        assertThat(quotidien.fillRate()).isEqualTo(25.0);
        assertThat(quotidien.orphanRate()).isEqualTo(50.0);

        assertThat(response.shortageRoutes()).hasSize(1);
        AdminLiquidityResponse.ShortageRoute route = response.shortageRoutes().get(0);
        assertThat(route.searchesWithoutResults()).isEqualTo(9);
        assertThat(route.lastSearchedAt()).isEqualTo(Instant.ofEpochSecond(1_756_900_000L));
    }

    @Test
    void toCsv_usesFrenchSpreadsheetConventions() {
        Instant periodStart = NOW.minus(30, ChronoUnit.DAYS);
        when(searchEventRepository.getFunnelStats(eq(periodStart), eq(NOW))).thenReturn(funnel(3, 2, 0, 0));
        SearchEventRepository.ShortageRoute shortage = mock(SearchEventRepository.ShortageRoute.class);
        when(shortage.getOrigin()).thenReturn("Cotonou; gare Jonquet");
        when(shortage.getDestination()).thenReturn("Lomé");
        when(shortage.getSearches()).thenReturn(1L);
        when(shortage.getWithoutResults()).thenReturn(1L);
        when(shortage.getLastSearchedEpoch()).thenReturn(null);
        when(searchEventRepository.findShortageRoutes(eq(periodStart), eq(NOW), anyInt())).thenReturn(List.of(shortage));

        String csv = service.toCsv(service.compute(30));

        assertThat(csv).startsWith("﻿indicateur;periode_courante;periode_precedente\r\n");
        assertThat(csv).contains("taux_recherche_aboutie_pct;66,7;0,0\r\n");
        // Delai median absent : cellule vide, jamais "0" ni "null".
        assertThat(csv).contains("delai_median_premiere_reservation_h;;\r\n");
        // Un libelle contenant le separateur est mis entre guillemets.
        assertThat(csv).contains("\"Cotonou; gare Jonquet\";Lomé;1;1;\r\n");
    }

    @Test
    void percent_neverDividesByZero() {
        assertThat(AdminLiquidityService.percent(0, 0)).isZero();
        assertThat(AdminLiquidityService.percent(5, 0)).isZero();
        assertThat(AdminLiquidityService.percent(1, 3)).isEqualTo(33.3);
        assertThat(AdminLiquidityService.percent(2, 3)).isEqualTo(66.7);
    }

    // ------------------------------------------------------------- fixtures
    // Implementations directes des projections (pas de mock Mockito : un mock
    // construit a l'interieur d'un when(...).thenReturn(...) laisserait le
    // stubbing externe inacheve).

    private record FunnelFixture(long total, long withResults, long byUsers, long converted)
            implements SearchEventRepository.FunnelStats {
        @Override public long getTotal() { return total; }
        @Override public long getWithResults() { return withResults; }
        @Override public long getByUsers() { return byUsers; }
        @Override public long getConverted() { return converted; }
    }

    private record ModeFixture(String tripType, long trips, long seatsPublished, long seatsBooked, long orphanTrips)
            implements TripRepository.ModeFillStats {
        @Override public String getTripType() { return tripType; }
        @Override public long getTrips() { return trips; }
        @Override public long getSeatsPublished() { return seatsPublished; }
        @Override public long getSeatsBooked() { return seatsBooked; }
        @Override public long getOrphanTrips() { return orphanTrips; }
    }

    private record DelayFixture(Double medianHours, long sampleSize) implements TripRepository.FirstBookingDelayStats {
        @Override public Double getMedianHours() { return medianHours; }
        @Override public long getSampleSize() { return sampleSize; }
    }

    private static SearchEventRepository.FunnelStats funnel(long total, long withResults, long byUsers, long converted) {
        return new FunnelFixture(total, withResults, byUsers, converted);
    }

    private static TripRepository.ModeFillStats mode(String tripType, long trips, long published, long booked, long orphans) {
        return new ModeFixture(tripType, trips, published, booked, orphans);
    }

    private static TripRepository.FirstBookingDelayStats delay(Double medianHours, long sampleSize) {
        return new DelayFixture(medianHours, sampleSize);
    }
}
