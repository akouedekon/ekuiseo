package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.dto.admin.AdminStatsResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Statistiques agregees pour le tableau de bord admin (trajets, reservations, volume,
 * revenus par periode). Phase 2 de l audit (constats F016/F118/F238) : tout est calcule
 * par agregations SQL (group by jour civil du Benin, par statut, par axe) - plus aucun
 * chargement de reservations en memoire - et {@code days} est borne a [1, 365] comme
 * pour AdminLiquidityService.
 */
@Service
public class AdminStatsService {

    static final int MAX_DAYS = 365;
    private static final List<String> COUNTED_STATUSES =
            List.of(BookingStatus.CONFIRMED.name(), BookingStatus.COMPLETED.name());
    private static final int TOP_ROUTES_LIMIT = 10;

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public AdminStatsService(TripRepository tripRepository, BookingRepository bookingRepository,
                              UserRepository userRepository) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    /**
     * Tableau de bord admin sur les {@code days} derniers jours (GET
     * /api/v1/admin/stats?days=N, contrat front voir AdminStatsResponse) : serie
     * journaliere, totaux, variation vs la periode precedente de meme duree, et
     * axes les plus demandes.
     */
    @Transactional(readOnly = true)
    public AdminStatsResponse computeStats(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("Le parametre days doit etre compris entre 1 et " + MAX_DAYS);
        }
        Instant now = Instant.now();
        Instant periodStart = now.minus(days, ChronoUnit.DAYS);
        Instant previousStart = periodStart.minus(days, ChronoUnit.DAYS);

        long totalTrips = tripRepository.countCreatedBetween(periodStart, now);
        long previousTrips = tripRepository.countCreatedBetween(previousStart, periodStart);
        BookingRepository.PeriodTotals current = bookingRepository.getPeriodTotals(periodStart, now, COUNTED_STATUSES);
        BookingRepository.PeriodTotals previous = bookingRepository.getPeriodTotals(previousStart, periodStart, COUNTED_STATUSES);
        long activeUsers = bookingRepository.countDistinctPassengersBetween(periodStart, now);
        long newUsers = userRepository.countByCreatedAtBetween(periodStart, now);
        long prevActiveUsers = bookingRepository.countDistinctPassengersBetween(previousStart, periodStart);
        long prevNewUsers = userRepository.countByCreatedAtBetween(previousStart, periodStart);

        AdminStatsResponse.Totals totals = new AdminStatsResponse.Totals(
                totalTrips, current.getBookings(), current.getGmv(), current.getRevenue(), activeUsers, newUsers);
        AdminStatsResponse.Deltas deltas = new AdminStatsResponse.Deltas(
                percentDelta(totalTrips, previousTrips), percentDelta(current.getBookings(), previous.getBookings()),
                percentDelta(current.getGmv(), previous.getGmv()), percentDelta(current.getRevenue(), previous.getRevenue()),
                percentDelta(activeUsers, prevActiveUsers), percentDelta(newUsers, prevNewUsers));

        List<AdminStatsResponse.DaySeries> series = buildSeries(days,
                tripRepository.countCreatedByDay(periodStart, now),
                bookingRepository.getDailyStats(periodStart, now, COUNTED_STATUSES));
        List<AdminStatsResponse.StatusCount> bookingsByStatus = bookingsByStatus(
                bookingRepository.countByStatusBetween(periodStart, now));
        List<AdminStatsResponse.RouteStat> topRoutes = bookingRepository
                .getTopRoutes(periodStart, now, COUNTED_STATUSES, TOP_ROUTES_LIMIT).stream()
                .map(r -> new AdminStatsResponse.RouteStat(r.getOrigin(), r.getDestination(), r.getTrips(), r.getGmv()))
                .toList();

        return new AdminStatsResponse(series, totals, deltas, bookingsByStatus, topRoutes);
    }

    /**
     * Un point par jour civil du Benin sur les {@code days} derniers jours (aujourd hui
     * inclus), a zero quand rien ne s est passe ; les jours hors de cette fenetre
     * (bord de periode) sont ignores.
     */
    private List<AdminStatsResponse.DaySeries> buildSeries(int days, List<TripRepository.DayCount> trips,
                                                           List<BookingRepository.DayStats> bookings) {
        Map<LocalDate, long[]> byDay = new TreeMap<>(); // [trips, bookings, gmv, revenue]
        LocalDate today = LocalDate.now(Tz.BENIN);
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), new long[4]);
        }
        for (TripRepository.DayCount t : trips) {
            long[] bucket = byDay.get(LocalDate.parse(t.getDay()));
            if (bucket != null) bucket[0] = t.getCount();
        }
        for (BookingRepository.DayStats b : bookings) {
            long[] bucket = byDay.get(LocalDate.parse(b.getDay()));
            if (bucket == null) continue;
            bucket[1] = b.getBookings();
            bucket[2] = b.getGmv();
            bucket[3] = b.getRevenue();
        }
        List<AdminStatsResponse.DaySeries> series = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : byDay.entrySet()) {
            long[] v = e.getValue();
            series.add(new AdminStatsResponse.DaySeries(e.getKey(), v[0], v[1], v[2], v[3]));
        }
        return series;
    }

    /** Variation en % ; convention : 0 si les deux periodes sont a 0, +100% si on part de 0 vers une valeur positive. */
    static double percentDelta(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0.0 : 100.0;
        }
        return Math.round(((double) (current - previous) / previous) * 1000.0) / 10.0;
    }

    /** Dans l ordre de declaration de BookingStatus, statuts a zero omis (meme forme qu avant). */
    private List<AdminStatsResponse.StatusCount> bookingsByStatus(List<BookingRepository.StatusCount> counts) {
        List<AdminStatsResponse.StatusCount> result = new ArrayList<>();
        for (BookingStatus status : BookingStatus.values()) {
            counts.stream().filter(c -> status.name().equals(c.getStatus())).findFirst()
                    .filter(c -> c.getCount() > 0)
                    .ifPresent(c -> result.add(new AdminStatsResponse.StatusCount(status, c.getCount())));
        }
        return result;
    }
}
