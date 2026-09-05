package bj.ekuiseo.api.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue admin d'un utilisateur (back-office), GET /api/v1/admin/users?q=...
 *
 * <p><b>Forme alignee sur le contrat front</b> (extended.ts) : remplace une forme
 * anterieure paginee et filtrable par statut (status/role/ratingCount/
 * lateCancellationsCount/suspendedReason/suspendedAt en moins, suspended/
 * tripsPublished/bookingsMade en plus, calcules par AdminUserService#search) -
 * voir README pour la justification de ce remplacement plutot qu'un doublage
 * d'endpoint (meme chemin, contrat de requete et de reponse tous deux differents
 * du front, contrairement aux renommages simples comme /activate -> /reinstate).</p>
 */
public record AdminUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String phone,
        String email,
        Instant createdAt,
        boolean identityVerified,
        boolean phoneVerified,
        boolean suspended,
        long tripsPublished,
        long bookingsMade,
        BigDecimal ratingAvg,
        /** Date d anonymisation (statut DELETED, V14) ; null pour un compte vivant. */
        Instant anonymizedAt
) {
}
