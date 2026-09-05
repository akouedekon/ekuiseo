package bj.ekuiseo.api.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Constat F408 : le rayon effectif ne depasse jamais la moitie de l axe cherche, ni ne descend sous 1 km. */
class TripServiceRadiusTest {

    // Referentiel geo_places (V3) : Cotonou et Abomey-Calavi, a 9,6 km environ.
    private static final double COTONOU_LAT = 6.3703, COTONOU_LNG = 2.3912;
    private static final double CALAVI_LAT = 6.4489, CALAVI_LNG = 2.3556;
    private static final double PARAKOU_LAT = 9.3372, PARAKOU_LNG = 2.6303;

    @Test
    void haversine_matchesKnownDistances() {
        assertThat(TripService.haversineKm(COTONOU_LAT, COTONOU_LNG, CALAVI_LAT, CALAVI_LNG)).isCloseTo(9.6, within(0.3));
        assertThat(TripService.haversineKm(COTONOU_LAT, COTONOU_LNG, PARAKOU_LAT, PARAKOU_LNG)).isCloseTo(331, within(5.0));
    }

    @Test
    void shortAxis_capsRadiusToHalfTheDistance() {
        // Le front envoie 15 km : sur Cotonou <-> Calavi, on retombe a ~4,8 km.
        double radius = TripService.effectiveRadiusKm(15, COTONOU_LAT, COTONOU_LNG, CALAVI_LAT, CALAVI_LNG);
        assertThat(radius).isCloseTo(4.8, within(0.2));
    }

    @Test
    void longAxis_keepsRequestedRadius() {
        assertThat(TripService.effectiveRadiusKm(15, COTONOU_LAT, COTONOU_LNG, PARAKOU_LAT, PARAKOU_LNG)).isEqualTo(15);
    }

    @Test
    void neverBelowOneKilometre() {
        // Deux quartiers voisins (1,2 km) : la moitie ferait 0,6 km, on garde 1 km.
        assertThat(TripService.effectiveRadiusKm(5, 6.3703, 2.3912, 6.3703, 2.4020)).isEqualTo(TripService.MIN_RADIUS_KM);
    }
}
