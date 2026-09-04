package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GeoPlaceMapper {
    GeoPlaceResponse toResponse(GeoPlace geoPlace);
}
