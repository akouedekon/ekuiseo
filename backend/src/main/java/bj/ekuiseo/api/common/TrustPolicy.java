package bj.ekuiseo.api.common;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.TrustLevel;

import java.math.BigDecimal;

/**
 * Niveau de confiance d un conducteur (contrat A.9), fonction pure des donnees deja portees par
 * {@code users} (identite verifiee, trajets termines comme conducteur - compteur V27 -, note et
 * nombre d avis) : aucune requete par trajet dans les resultats de recherche.
 * <ul>
 *   <li>UNVERIFIED : identite non verifiee ;</li>
 *   <li>VERIFIED : identite verifiee ;</li>
 *   <li>EXPERIENCED : identite verifiee ET au moins {@link #EXPERIENCED_MIN_TRIPS} trajets termines
 *       comme conducteur ET note >= {@link #EXPERIENCED_MIN_RATING} sur au moins
 *       {@link #EXPERIENCED_MIN_REVIEWS} avis.</li>
 * </ul>
 */
public final class TrustPolicy {

    public static final int EXPERIENCED_MIN_TRIPS = 10;
    public static final int EXPERIENCED_MIN_REVIEWS = 5;
    public static final BigDecimal EXPERIENCED_MIN_RATING = new BigDecimal("4.5");

    private TrustPolicy() {
    }

    public static TrustLevel of(boolean identityVerified, int tripsCompletedAsDriver, BigDecimal ratingAvg, int ratingCount) {
        if (!identityVerified) {
            return TrustLevel.UNVERIFIED;
        }
        boolean experienced = tripsCompletedAsDriver >= EXPERIENCED_MIN_TRIPS
                && ratingCount >= EXPERIENCED_MIN_REVIEWS
                && ratingAvg != null && ratingAvg.compareTo(EXPERIENCED_MIN_RATING) >= 0;
        return experienced ? TrustLevel.EXPERIENCED : TrustLevel.VERIFIED;
    }

    public static TrustLevel of(User user) {
        if (user == null) {
            return TrustLevel.UNVERIFIED;
        }
        return of(user.isIdentityVerified(), user.getTripsCompletedAsDriver(), user.getRatingAvg(), user.getRatingCount());
    }
}
