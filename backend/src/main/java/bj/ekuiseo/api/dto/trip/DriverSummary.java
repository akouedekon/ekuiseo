package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.domain.enums.TrustLevel;

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
        boolean identityVerified,
        /** Niveau de confiance (contrat A.9) : UNVERIFIED, VERIFIED, EXPERIENCED (voir TrustPolicy). */
        TrustLevel trustLevel,
        /** Trajets termines comme conducteur (compteur V27). */
        int tripsCompletedAsDriver
) {
    /** Meme conducteur, nom de famille reduit a son initiale pour un appelant anonyme (constat F519). */
    public DriverSummary anonymized() {
        return new DriverSummary(id, firstName, Masking.lastNameInitial(lastName), photoUrl, ratingAvg, ratingCount,
                identityVerified, trustLevel, tripsCompletedAsDriver);
    }
}
