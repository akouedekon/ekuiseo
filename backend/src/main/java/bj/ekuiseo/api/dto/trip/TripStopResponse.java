package bj.ekuiseo.api.dto.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * Arret intermediaire d'un trajet, avec son prix par troncon depuis l'origine
 * (GET /api/v1/trips/{id}/stops). La position 0 designe conventionnellement
 * l'origine du trajet elle-meme (jamais materialisee comme une ligne trip_stops) ;
 * les arrets stockes commencent donc a la position 1 (voir TripService#createTrip).
 */
public record TripStopResponse(
        UUID id,
        String label,
        double lat,
        double lng,
        Instant plannedAt,
        long priceFromOrigin,
        int position
) {
}
