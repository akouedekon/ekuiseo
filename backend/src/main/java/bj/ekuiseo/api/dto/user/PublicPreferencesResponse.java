package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.domain.enums.ChattyLevel;

/**
 * Sous-ensemble PUBLIC des preferences a bord d'un utilisateur, expose sur le
 * profil public (GET /api/v1/users/{id}.preferences) consulte par un tiers
 * avant de reserver aupres de lui. Reprend UNIQUEMENT les preferences a bord
 * de {@link UserPreferencesResponse} (musique, conversation, fumeur, animaux)
 * - jamais les canaux de notification (push/SMS/e-mail) ni la langue, qui
 * restent prives (reserves a GET /api/v1/me/preferences).
 *
 * <p>Absence de ligne {@code user_preferences} pour cet utilisateur = valeurs
 * par defaut (regle metier n.17, meme semantique que le profil prive) - voir
 * UserService#resolvePublicPreferences, qui ne cree jamais de ligne en
 * consultant le profil d'un tiers (contrairement a UserPreferencesService,
 * cree paresseusement uniquement sur SON PROPRE profil).</p>
 */
public record PublicPreferencesResponse(
        boolean smoking,
        boolean music,
        boolean pets,
        ChattyLevel chatty
) {
}
