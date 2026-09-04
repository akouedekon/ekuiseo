package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.dto.trip.VehicleSummary;
import bj.ekuiseo.api.dto.user.VehicleResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface VehicleMapper {
    VehicleResponse toResponse(Vehicle vehicle);
    VehicleSummary toSummary(Vehicle vehicle);
}
