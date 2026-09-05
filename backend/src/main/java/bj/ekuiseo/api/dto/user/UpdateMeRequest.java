package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mise a jour du profil prive (PATCH /api/v1/me). Champs absents = inchanges.
 * L adresse e-mail n en fait plus partie : c est le canal des codes de connexion, elle
 * se change par le parcours verifie POST /me/email/request puis /me/email/confirm.
 */
public record UpdateMeRequest(
        @Size(min = 1, max = 100) String firstName,
        @Size(min = 1, max = 100) String lastName,
        @Size(max = 300) String bio,
        @Size(max = 500) @Pattern(regexp = "^https://.*", message = "L URL de la photo doit commencer par https://") String photoUrl
) {
}
