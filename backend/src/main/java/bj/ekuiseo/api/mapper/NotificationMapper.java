package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Notification;
import bj.ekuiseo.api.dto.notification.NotificationResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationMapper {
    NotificationResponse toResponse(Notification notification);
}
