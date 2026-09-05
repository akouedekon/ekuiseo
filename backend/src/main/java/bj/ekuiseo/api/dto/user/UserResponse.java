package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.Role;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Profil de l'utilisateur connecte (GET /api/v1/me, AuthResponse.user).
 * {@code role} permet au front de n'afficher l'entree « Back-office » qu'aux
 * comptes ADMIN ; la securite reelle reste cote serveur (/api/v1/admin/**).
 */
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
        boolean emailVerified,
        boolean identityVerified,
        Role role
) {
}
