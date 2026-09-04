package bj.ekuiseo.api.dto.geo;

import java.util.UUID;

public record GeoPlaceResponse(
        UUID id,
        String name,
        String region,
        String countryCode,
        String kind,
        double lat,
        double lng
) {
}
