package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constat F419 : heures de passage des arrets bornees et croissantes. Constats F115/F409 :
 * troncon apparie (montee / descente / prix) d une recherche passant par un arret. F137 : tri.
 */
class TripServiceStopsTest {

    private static final double COTONOU_LAT = 6.3703, COTONOU_LNG = 2.3912;
    private static final double BOHICON_LAT = 7.1783, BOHICON_LNG = 2.0667;
    private static final double PARAKOU_LAT = 9.3372, PARAKOU_LNG = 2.6303;

    private final Instant departure = Instant.parse("2026-09-12T06:30:00Z");

    @Test
    void stopTimes_mustStayWithin24h_andIncrease() {
        assertThatCode(() -> TripService.validateStopTimes(departure, List.of(departure.plus(2, ChronoUnit.HOURS), departure.plus(5, ChronoUnit.HOURS))))
                .doesNotThrowAnyException();
        assertThatCode(() -> TripService.validateStopTimes(departure, Arrays.asList(null, departure.plus(1, ChronoUnit.HOURS))))
                .doesNotThrowAnyException();
        assertThatCode(() -> TripService.validateStopTimes(departure, List.of())).doesNotThrowAnyException();

        assertThatThrownBy(() -> TripService.validateStopTimes(departure, List.of(departure.minus(1, ChronoUnit.MINUTES))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("anterieure au depart");
        assertThatThrownBy(() -> TripService.validateStopTimes(departure, List.of(departure.plus(25, ChronoUnit.HOURS))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("24 h");
        assertThatThrownBy(() -> TripService.validateStopTimes(departure,
                List.of(departure.plus(3, ChronoUnit.HOURS), departure.plus(3, ChronoUnit.HOURS))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("arret 2");
        assertThatThrownBy(() -> TripService.validateStopTimes(departure,
                List.of(departure.plus(3, ChronoUnit.HOURS), departure.plus(2, ChronoUnit.HOURS))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("posterieure");
    }

    private static Trip cotonouParakou() {
        return Trip.builder().id(UUID.randomUUID()).originLat(COTONOU_LAT).originLng(COTONOU_LNG)
                .destLat(PARAKOU_LAT).destLng(PARAKOU_LNG).pricePerSeat(6000).build();
    }

    private static TripStop bohicon() {
        return TripStop.builder().id(UUID.randomUUID()).position(1).label("Bohicon").lat(BOHICON_LAT).lng(BOHICON_LNG)
                .priceFromOrigin(2500).build();
    }

    @Test
    void segment_pickupAtIntermediateStop_pricesTheRemainingLeg() {
        TripStop stop = bohicon();
        TripService.Segment segment = TripService.matchSegment(cotonouParakou(), List.of(stop),
                BOHICON_LAT, BOHICON_LNG, PARAKOU_LAT, PARAKOU_LNG, 15);

        assertThat(segment).isNotNull();
        assertThat(segment.pickupStopId()).isEqualTo(stop.getId());
        assertThat(segment.dropoffStopId()).isNull();
        assertThat(segment.priceFcfa()).isEqualTo(3500);
    }

    @Test
    void segment_dropoffAtIntermediateStop_pricesTheFirstLeg() {
        TripStop stop = bohicon();
        TripService.Segment segment = TripService.matchSegment(cotonouParakou(), List.of(stop),
                COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG, 15);

        assertThat(segment).isNotNull();
        assertThat(segment.pickupStopId()).isNull();
        assertThat(segment.dropoffStopId()).isEqualTo(stop.getId());
        assertThat(segment.priceFcfa()).isEqualTo(2500);
    }

    @Test
    void segment_isNull_forFullTrip_wrongDirection_orNoMatch() {
        assertThat(TripService.matchSegment(cotonouParakou(), List.of(bohicon()), COTONOU_LAT, COTONOU_LNG, PARAKOU_LAT, PARAKOU_LNG, 15)).isNull();
        assertThat(TripService.matchSegment(cotonouParakou(), List.of(bohicon()), PARAKOU_LAT, PARAKOU_LNG, BOHICON_LAT, BOHICON_LNG, 15)).isNull();
        assertThat(TripService.matchSegment(cotonouParakou(), List.of(bohicon()), BOHICON_LAT, BOHICON_LNG, COTONOU_LAT, COTONOU_LNG, 15)).isNull();
        assertThat(TripService.matchSegment(cotonouParakou(), List.of(), BOHICON_LAT, BOHICON_LNG, PARAKOU_LAT, PARAKOU_LNG, 15)).isNull();
    }

    @Test
    void searchSort_parsesKnownValues_andRefusesOthers() {
        assertThat(TripService.SearchSort.from(null)).isEqualTo(TripService.SearchSort.DEPARTURE);
        assertThat(TripService.SearchSort.from(" Price ")).isEqualTo(TripService.SearchSort.PRICE);
        assertThat(TripService.SearchSort.from("rating")).isEqualTo(TripService.SearchSort.RATING);
        assertThatThrownBy(() -> TripService.SearchSort.from("distance")).isInstanceOf(BadRequestException.class);
    }
}
