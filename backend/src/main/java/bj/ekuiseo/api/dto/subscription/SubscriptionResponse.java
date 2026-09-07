package bj.ekuiseo.api.dto.subscription;

import bj.ekuiseo.api.domain.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Etat de l abonnement conducteur (GET /api/v1/me/subscription) : l abonnement ACTIVE non
 * echu s il existe, sinon le plus recent. {@code renewable} = aucune periode active, ou
 * echeance a moins de 7 jours (POST /me/subscription accepte alors un renouvellement dont
 * la periode demarre a la fin de l actuelle).
 */
public record SubscriptionResponse(
        UUID id,
        long priceFcfa,
        SubscriptionStatus status,
        boolean currentlyActive,
        Instant startedAt,
        Instant currentPeriodEnd,
        boolean renewable
) {
}
