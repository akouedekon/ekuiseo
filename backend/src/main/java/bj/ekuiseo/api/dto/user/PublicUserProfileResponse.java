package bj.ekuiseo.api.dto.user;

import bj.ekuiseo.api.dto.trip.VehicleSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Profil public d'un utilisateur (typiquement consulte avant de reserver un
 * trajet aupres d'un conducteur). Expose volontairement uniquement des
 * informations non sensibles : ni telephone, ni e-mail, ni date de naissance
 * (voir UserPublicController et UserService#getPublicProfile).
 *
 * <p>{@code reliabilityRate}, {@code responseTimeMinutes} et {@code preferences}
 * sont calcules par des requetes d'agregation dediees (jamais par le chargement
 * d'une collection de reservations/messages en memoire - ce profil est consulte
 * frequemment) - voir UserService#getPublicProfile, BookingRepository#getReliabilityStats
 * et MessageRepository#getResponseTimeStats.</p>
 */
public record PublicUserProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String photoUrl,
        String bio,
        BigDecimal ratingAvg,
        int ratingCount,
        boolean phoneVerified,
        boolean identityVerified,
        Instant memberSince,
        long tripsCompleted,
        List<VehicleSummary> vehicles,
        /** % entier de trajets honores (COMPLETED / (COMPLETED + annulations conducteur tardives + NO_SHOW)) ; {@code null} en dessous de 5 trajets mesurables. */
        Integer reliabilityRate,
        /** Delai median (minutes) entre le premier message d'un passager et la premiere reponse du conducteur, 90 derniers jours ; {@code null} en dessous de 5 echanges mesurables. */
        Integer responseTimeMinutes,
        /** Preferences a bord publiques uniquement (jamais les preferences de notification) ; jamais {@code null} (valeurs par defaut si aucune ligne enregistree, regle n.17). */
        PublicPreferencesResponse preferences
) {
}
