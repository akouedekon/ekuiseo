package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BookingMapper {

    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "passengerId", source = "passenger.id")
    BookingResponse toResponse(Booking booking);
}
