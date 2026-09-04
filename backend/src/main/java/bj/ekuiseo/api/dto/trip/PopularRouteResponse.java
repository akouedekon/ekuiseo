package bj.ekuiseo.api.dto.trip;

/**
 * Axe le plus propose en ce moment (GET /api/v1/trips/popular), pour les raccourcis
 * de l'accueil : libelles et coordonnees moyennes d'origine/destination, nombre de
 * trajets PUBLISHED a venir avec au moins une place, et prix plancher par place.
 * Public, sans aucune donnee personnelle.
 */
public record PopularRouteResponse(
        String originLabel,
        double originLat,
        double originLng,
        String destLabel,
        double destLat,
        double destLng,
        long trips,
        long minPrice
) {
}
