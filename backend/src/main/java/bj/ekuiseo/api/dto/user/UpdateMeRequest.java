package bj.ekuiseo.api.dto.user;

import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Mise a jour du profil prive (PATCH /api/v1/me). Champs absents = inchanges ; une
 * presentation ({@code bio}) vide efface la presentation (constat F502).
 * L adresse e-mail n en fait plus partie : c est le canal des codes de connexion, elle
 * se change par le parcours verifie POST /me/email/request puis /me/email/confirm.
 * {@code photoUrl} est refuse tant qu aucun stockage de fichiers maitrise n existe
 * (constat F401) : une URL externe rendue chez tous les visiteurs serait une balise de
 * pistage ; le champ reste dans le contrat pour le jour ou le televersement existera.
 */
public record UpdateMeRequest(
        @Size(min = 1, max = 100) String firstName,
        @Size(min = 1, max = 100) String lastName,
        @Size(max = 300) String bio,
        @Null(message = "La photo de profil ne peut pas encore etre renseignee : le televersement n est pas disponible") String photoUrl
) {
}
