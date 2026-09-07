package bj.ekuiseo.api.dto.geo;

import bj.ekuiseo.api.domain.enums.GeoPlaceKind;

import java.util.UUID;

/**
 * Lieu du referentiel (GET /api/v1/geo/search, GET /api/v1/geo/places). {@code parentId} /
 * {@code parentName} designent la ville de rattachement d un quartier ou d une gare
 * (null pour une ville), pour afficher « Agla - Cotonou » (constat F422) ; {@code kind}
 * permet au front d adapter le rayon de recherche (ville : 15 km, quartier ou gare : 3-5 km).
 */
public record GeoPlaceResponse(
        UUID id,
        String name,
        String region,
        String countryCode,
        GeoPlaceKind kind,
        double lat,
        double lng,
        UUID parentId,
        String parentName
) {
}
