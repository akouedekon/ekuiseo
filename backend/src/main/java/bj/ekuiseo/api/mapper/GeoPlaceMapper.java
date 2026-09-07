package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GeoPlaceMapper {

    /** {@code parentName} est resolu par l appelant (GeocodingService) en une requete pour toute la liste. */
    @Mapping(target = "id", source = "geoPlace.id")
    @Mapping(target = "name", source = "geoPlace.name")
    @Mapping(target = "region", source = "geoPlace.region")
    @Mapping(target = "countryCode", source = "geoPlace.countryCode")
    @Mapping(target = "kind", source = "geoPlace.kind")
    @Mapping(target = "lat", source = "geoPlace.lat")
    @Mapping(target = "lng", source = "geoPlace.lng")
    @Mapping(target = "parentId", source = "geoPlace.parentPlaceId")
    @Mapping(target = "parentName", source = "parentName")
    GeoPlaceResponse toResponse(GeoPlace geoPlace, String parentName);
}
