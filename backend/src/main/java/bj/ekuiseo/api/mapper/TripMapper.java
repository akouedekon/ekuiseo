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
    @Mapping(target = "pickupStopId", ignore = true)
    @Mapping(target = "dropoffStopId", ignore = true)
    @Mapping(target = "segmentPriceFcfa", ignore = true)
    TripResponse toResponse(Trip trip);

    /** Niveau de confiance calcule depuis les colonnes de users (contrat A.9), sans requete supplementaire. */
    @Mapping(target = "trustLevel", expression = "java(bj.ekuiseo.api.common.TrustPolicy.of(user))")
    DriverSummary toDriverSummary(bj.ekuiseo.api.domain.User user);

    TripStopResponse toStopResponse(TripStop stop);
}
