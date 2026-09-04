package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.dto.message.MessageResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MessageMapper {

    @Mapping(target = "conversationId", source = "conversation.id")
    @Mapping(target = "senderId", source = "sender.id")
    MessageResponse toResponse(Message message);
}
