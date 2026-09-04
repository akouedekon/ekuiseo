package bj.ekuiseo.api.dto.admin;

import java.time.Instant;

/**
 * Statistiques agregees pour le tableau de bord admin, sur une periode
 * [from, to) donnee (voir AdminStatsController).
 */
public record StatsResponse(
        Instant from,
        Instant to,
        long tripsCreated,
        long bookingsCreated,
        long newUsers,
        long grossVolumeFcfa,
        long platformRevenueFcfa
) {
}
