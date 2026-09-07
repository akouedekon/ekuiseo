package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.service.TermsPolicy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Profil prive. {@code termsAcceptanceRequired} est calcule par {@link TermsPolicy}
 * (version en vigueur des CGU comparee a celle acceptee par le compte, constat F509).
 */
@Mapper(componentModel = "spring")
public abstract class UserMapper {

    @Autowired
    protected TermsPolicy termsPolicy;

    @Mapping(target = "termsAcceptanceRequired", expression = "java(termsPolicy != null && termsPolicy.acceptanceRequired(user))")
    public abstract UserResponse toResponse(User user);
}
