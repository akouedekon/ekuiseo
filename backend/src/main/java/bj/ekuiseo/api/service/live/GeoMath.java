package bj.ekuiseo.api.service.live;

/** Distances a vol d oiseau pour le suivi en direct (V28) : assez precises pour 150 m, 1 km ou 200 km/h. */
final class GeoMath {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private GeoMath() {
    }

    /** Distance en metres entre deux points (formule de haversine). */
    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Vitesse en km/h impliquee par un deplacement ; un intervalle nul ou negatif compte pour une seconde. */
    static double impliedSpeedKmh(double meters, long millis) {
        double seconds = Math.max(1.0, millis / 1000.0);
        return meters / seconds * 3.6;
    }
}
