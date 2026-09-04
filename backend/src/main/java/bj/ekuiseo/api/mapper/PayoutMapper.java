package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PayoutMapper {

    @Mapping(target = "driverId", source = "driver.id")
    PayoutResponse toResponse(DriverPayout payout);
}
