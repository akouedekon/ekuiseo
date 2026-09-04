package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.TripType;

import java.time.Instant;
import java.util.List;

/**
 * Indicateurs de liquidite du back-office, GET /api/v1/admin/stats/liquidity?days=N
 * (voir AdminLiquidityService). Chaque bloc repond a une question du fondateur :
 * <ul>
 *   <li>{@code northStar} - avance-t-on vers le seuil de viabilite (2 000 places
 *       confirmees par mois) ?</li>
 *   <li>{@code current} / {@code previous} - les passagers trouvent-ils, les
 *       conducteurs remplissent-ils ? Les deux periodes sont renvoyees telles quelles
 *       (meme forme) pour que le front affiche la variation en points, un taux seul ne
 *       s'interpretant pas.</li>
 *   <li>{@code fillByMode} - le mode quotidien, coeur du modele economique, decolle-t-il ?</li>
 *   <li>{@code shortageRoutes} - quels corridors demarcher en priorite ?</li>
 * </ul>
 * Tous les taux sont en pourcentage, arrondis au dixieme. Un delai median absent
 * (aucun trajet reserve sur la periode) est {@code null}, jamais 0.
 */
public record AdminLiquidityResponse(
        Period period,
        NorthStar northStar,
        Headline current,
        Headline previous,
        List<ModeFill> fillByMode,
        List<RouteFill> fillByRoute,
        List<ShortageRoute> shortageRoutes
) {
    /** Fenetre analysee [from, to) ; la periode precedente est [from - days, from). */
    public record Period(int days, Instant from, Instant to) {
    }

    /**
     * Metrique nord : places confirmees (reservations CONFIRMED, COMPLETED ou NO_SHOW,
     * c'est-a-dire reellement vendues) sur la periode. {@code monthlyPace} extrapole
     * la periode a 30 jours pour la comparer au seuil {@code monthlyTarget}.
     */
    public record NorthStar(long confirmedSeats, long previousConfirmedSeats, double monthlyPace,
                            long monthlyTarget, double progressPercent, List<WeekSeats> weekly) {
    }

    /** Places confirmees par semaine civile (lundi = {@code weekStart}, au format ISO AAAA-MM-JJ). */
    public record WeekSeats(String weekStart, long seats) {
    }

    /**
     * Indicateurs de liquidite d'une periode.
     * <ul>
     *   <li>Recherche : {@code searchSuccessRate} = recherches avec au moins un trajet /
     *       total ; {@code searchToBookingRate} = recherches d'utilisateurs connectes
     *       suivies d'une reservation du meme utilisateur sous 24 h / recherches
     *       d'utilisateurs connectes (les recherches anonymes ne sont pas attribuables).</li>
     *   <li>Offre : trajets partis sur la periode (hors brouillons et annules) ;
     *       {@code fillRate} = places reservees / places publiees ; {@code orphanRate} =
     *       trajets sans aucune reservation / trajets.</li>
     *   <li>{@code medianHoursToFirstBooking} : delai median entre la publication d'un
     *       trajet (cree sur la periode) et sa premiere reservation, sur
     *       {@code firstBookingSampleSize} trajets ; null si aucun echantillon.</li>
     * </ul>
     */
    public record Headline(long searches, long searchesWithResults, double searchSuccessRate,
                           long searchesByUsers, long searchesConverted, double searchToBookingRate,
                           long trips, long seatsPublished, long seatsBooked, double fillRate,
                           long orphanTrips, double orphanRate,
                           Double medianHoursToFirstBooking, long firstBookingSampleSize) {
    }

    public record ModeFill(TripType tripType, long trips, long seatsPublished, long seatsBooked,
                           double fillRate, long orphanTrips, double orphanRate) {
    }

    /** Regroupe par libelles exacts d'origine/destination du trajet (meme convention que AdminStatsResponse.topRoutes). */
    public record RouteFill(String origin, String destination, TripType tripType, long trips,
                            long seatsPublished, long seatsBooked, double fillRate, long orphanTrips) {
    }

    /** Axe recherche sans resultat : {@code searchesWithoutResults} sur {@code searches} au total. */
    public record ShortageRoute(String origin, String destination, long searches,
                                long searchesWithoutResults, Instant lastSearchedAt) {
    }
}
