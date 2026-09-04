package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.BookingStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Tableau de bord admin, GET /api/v1/admin/stats?days=N (voir AdminStatsService#computeStats).
 * Toutes les valeurs monetaires sont en FCFA. {@code deltas} exprime la variation
 * en pourcentage par rapport a la periode precedente de meme duree
 * (ex : days=30 -> compare aux 30 jours precedant la periode analysee).
 */
public record AdminStatsResponse(
        List<DaySeries> series,
        Totals totals,
        Deltas deltas,
        List<StatusCount> bookingsByStatus,
        List<RouteStat> topRoutes
) {
    public record DaySeries(LocalDate date, long trips, long bookings, long gmv, long revenue) {
    }

    public record Totals(long trips, long bookings, long gmv, long revenue, long activeUsers, long newUsers) {
    }

    /** Variation en % vs periode precedente (positive = hausse). 0 si les deux periodes sont a 0. */
    public record Deltas(double trips, double bookings, double gmv, double revenue, double activeUsers, double newUsers) {
    }

    public record StatusCount(BookingStatus status, long count) {
    }

    public record RouteStat(String origin, String destination, long trips, long gmv) {
    }
}
