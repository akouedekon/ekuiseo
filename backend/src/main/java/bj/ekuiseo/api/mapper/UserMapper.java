package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.user.UserResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponse toResponse(User user);
}
