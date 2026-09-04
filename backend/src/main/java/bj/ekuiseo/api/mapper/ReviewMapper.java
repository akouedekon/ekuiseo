package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Review;
import bj.ekuiseo.api.dto.review.ReviewResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    @Mapping(target = "tripId", source = "trip.id")
    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "targetId", source = "target.id")
    ReviewResponse toResponse(Review review);
}
