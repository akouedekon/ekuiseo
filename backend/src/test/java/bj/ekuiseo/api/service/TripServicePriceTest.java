package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constats F040/F207 : plus de trajet ni d arret a 0 F, prix des arrets bornes et croissants. */
class TripServicePriceTest {

    @Test
    void acceptsPositiveIncreasingStopPricesBelowTheTripPrice() {
        assertThatCode(() -> TripService.validatePrices(3500, List.of(1500L, 2500L, 3500L))).doesNotThrowAnyException();
        assertThatCode(() -> TripService.validatePrices(3500, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> TripService.validatePrices(3500, List.of(2000L, 2000L))).doesNotThrowAnyException();
    }

    @Test
    void rejectsZeroPrices() {
        assertThatThrownBy(() -> TripService.validatePrices(0, List.of())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> TripService.validatePrices(3500, List.of(0L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("arret 1");
        assertThatThrownBy(() -> TripService.validatePrices(3500, List.of(1500L, 0L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("arret 2");
    }

    @Test
    void rejectsStopPricesAboveTripPriceOrDecreasing() {
        assertThatThrownBy(() -> TripService.validatePrices(3500, List.of(4000L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("depasse");
        assertThatThrownBy(() -> TripService.validatePrices(3500, List.of(2500L, 1500L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("arret precedent");
    }
}
