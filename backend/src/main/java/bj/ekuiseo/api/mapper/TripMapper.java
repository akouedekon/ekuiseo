package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.dto.trip.DriverSummary;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.dto.trip.TripStopResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {VehicleMapper.class})
public interface TripMapper {

    @Mapping(target = "driver", source = "driver")
    @Mapping(target = "vehicle", source = "vehicle")
    @Mapping(target = "generatedOccurrences", ignore = true)
    TripResponse toResponse(Trip trip);

    DriverSummary toDriverSummary(bj.ekuiseo.api.domain.User user);

    TripStopResponse toStopResponse(TripStop stop);
}
