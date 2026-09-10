package bj.ekuiseo.api.dto.trip;

/**
 * Depart proche d un point (GET /api/v1/trips/nearby, ecran « Autour de moi ») : le trajet
 * tel que la recherche l expose, la distance (km) entre le point cherche et le point de
 * montee le plus proche, et ce point de montee (origine du trajet ou arret intermediaire,
 * jamais la destination). Le trajet reste un depart planifie : il n y a pas de course a la
 * demande, le passager le reserve comme n importe quel autre.
 */
public record NearbyTripResponse(
        TripResponse trip,
        double distanceKm,
        String boardingLabel,
        double boardingLat,
        double boardingLng
) {
}
