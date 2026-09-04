package bj.ekuiseo.api.dto.user;

import java.math.BigDecimal;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String phone,
        String email,
        String firstName,
        String lastName,
        String photoUrl,
        String bio,
        BigDecimal ratingAvg,
        int ratingCount,
        boolean phoneVerified,
        boolean identityVerified
) {
}
