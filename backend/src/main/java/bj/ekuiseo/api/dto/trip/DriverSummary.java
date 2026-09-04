package bj.ekuiseo.api.dto.trip;

import java.math.BigDecimal;
import java.util.UUID;

public record DriverSummary(
        UUID id,
        String firstName,
        String lastName,
        String photoUrl,
        BigDecimal ratingAvg,
        int ratingCount,
        /** Badge de verification d'identite (regle metier n.19). Ajoute pour que le
         * front puisse filtrer "conducteurs verifies" sans avoir a l'approximer via
         * le nombre d'avis (voir extended.ts, PublicUserResponse.identityVerified). */
        boolean identityVerified
) {
}
