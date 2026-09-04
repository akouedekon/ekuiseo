package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.dto.admin.AdminStatsResponse;
import bj.ekuiseo.api.dto.admin.StatsResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Statistiques agregees pour le tableau de bord admin (trajets, reservations, volume, revenus par periode). */
@Service
public class AdminStatsService {

    private static final List<BookingStatus> COUNTED_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
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

    /** Forme historique (from/to explicites), conservee pour compatibilite - voir {@link #computeStats(int)} pour le contrat front (?days=N). */
    @Transactional(readOnly = true)
    public StatsResponse computeStats(Instant from, Instant to) {
        long tripsCreated = tripRepository.countByCreatedAtBetween(from, to);
        long bookingsCreated = bookingRepository.countByCreatedAtBetween(from, to);
        long newUsers = userRepository.countByCreatedAtBetween(from, to);
        long grossVolume = bookingRepository.sumAmountBetween(from, to, COUNTED_STATUSES);
        long platformRevenue = bookingRepository.sumServiceFeeBetween(from, to, COUNTED_STATUSES);
        return new StatsResponse(from, to, tripsCreated, bookingsCreated, newUsers, grossVolume, platformRevenue);
    }

    /**
     * Tableau de bord admin sur les {@code days} derniers jours (GET
     * /api/v1/admin/stats?days=N, contrat front voir AdminStatsResponse) : serie
     * journaliere, totaux, variation vs la periode precedente de meme duree, et
     * axes les plus demandes.
     */
    @Transactional(readOnly = true)
    public AdminStatsResponse computeStats(int days) {
        Instant now = Instant.now();
        Instant periodStart = now.minus(days, ChronoUnit.DAYS);
        Instant previousStart = periodStart.minus(days, ChronoUnit.DAYS);

        List<Instant> tripTimestamps = tripRepository.findCreatedAtBetween(periodStart, now);
        List<Booking> bookings = bookingRepository.findAllWithTripByCreatedAtBetween(periodStart, now);
        long previousTrips = tripRepository.findCreatedAtBetween(previousStart, periodStart).size();
        List<Booking> previousBookings = bookingRepository.findAllWithTripByCreatedAtBetween(previousStart, periodStart);

        List<AdminStatsResponse.DaySeries> series = buildSeries(days, tripTimestamps, bookings);

        long totalTrips = tripTimestamps.size();
        long totalBookings = bookings.size();
        long totalGmv = sumIfCounted(bookings, Booking::getAmount);
        long totalRevenue = sumIfCounted(bookings, Booking::getServiceFee);
        long activeUsers = bookingRepository.countDistinctPassengersBetween(periodStart, now);
        long newUsers = userRepository.countByCreatedAtBetween(periodStart, now);

        long prevBookingsCount = previousBookings.size();
        long prevGmv = sumIfCounted(previousBookings, Booking::getAmount);
        long prevRevenue = sumIfCounted(previousBookings, Booking::getServiceFee);
        long prevActiveUsers = bookingRepository.countDistinctPassengersBetween(previousStart, periodStart);
        long prevNewUsers = userRepository.countByCreatedAtBetween(previousStart, periodStart);

        AdminStatsResponse.Totals totals = new AdminStatsResponse.Totals(
                totalTrips, totalBookings, totalGmv, totalRevenue, activeUsers, newUsers);
        AdminStatsResponse.Deltas deltas = new AdminStatsResponse.Deltas(
                percentDelta(totalTrips, previousTrips), percentDelta(totalBookings, prevBookingsCount),
                percentDelta(totalGmv, prevGmv), percentDelta(totalRevenue, prevRevenue),
                percentDelta(activeUsers, prevActiveUsers), percentDelta(newUsers, prevNewUsers));

        List<AdminStatsResponse.StatusCount> bookingsByStatus = bookingsByStatus(bookings);
        List<AdminStatsResponse.RouteStat> topRoutes = topRoutes(bookings);

        return new AdminStatsResponse(series, totals, deltas, bookingsByStatus, topRoutes);
    }

    private List<AdminStatsResponse.DaySeries> buildSeries(int days, List<Instant> tripTimestamps, List<Booking> bookings) {
        Map<LocalDate, long[]> byDay = new TreeMap<>(); // [trips, bookings, gmv, revenue]
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), new long[4]);
        }
        for (Instant t : tripTimestamps) {
            LocalDate d = t.atZone(ZoneOffset.UTC).toLocalDate();
            long[] bucket = byDay.get(d);
            if (bucket != null) bucket[0]++;
        }
        for (Booking b : bookings) {
            LocalDate d = b.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            long[] bucket = byDay.get(d);
            if (bucket == null) continue;
            bucket[1]++;
            if (COUNTED_STATUSES.contains(b.getStatus())) {
                bucket[2] += b.getAmount();
                bucket[3] += b.getServiceFee();
            }
        }
        List<AdminStatsResponse.DaySeries> series = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : byDay.entrySet()) {
            long[] v = e.getValue();
            series.add(new AdminStatsResponse.DaySeries(e.getKey(), v[0], v[1], v[2], v[3]));
        }
        return series;
    }

    private long sumIfCounted(List<Booking> bookings, java.util.function.ToLongFunction<Booking> extractor) {
        return bookings.stream().filter(b -> COUNTED_STATUSES.contains(b.getStatus())).mapToLong(extractor).sum();
    }

    /** Variation en % ; convention : 0 si les deux periodes sont a 0, +100% si on part de 0 vers une valeur positive. */
    private double percentDelta(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0.0 : 100.0;
        }
        return Math.round(((double) (current - previous) / previous) * 1000.0) / 10.0;
    }

    private List<AdminStatsResponse.StatusCount> bookingsByStatus(List<Booking> bookings) {
        Map<BookingStatus, Long> counts = new LinkedHashMap<>();
        for (BookingStatus status : BookingStatus.values()) {
            counts.put(status, 0L);
        }
        for (Booking b : bookings) {
            counts.merge(b.getStatus(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new AdminStatsResponse.StatusCount(e.getKey(), e.getValue()))
                .toList();
    }

    private List<AdminStatsResponse.RouteStat> topRoutes(List<Booking> bookings) {
        Map<String, RouteAccumulator> byRoute = new LinkedHashMap<>();
        for (Booking b : bookings) {
            if (!COUNTED_STATUSES.contains(b.getStatus())) continue;
            Trip trip = b.getTrip();
            String key = trip.getOriginLabel() + "||" + trip.getDestLabel();
            RouteAccumulator acc = byRoute.computeIfAbsent(key, k -> new RouteAccumulator(trip.getOriginLabel(), trip.getDestLabel()));
            acc.tripIds.add(trip.getId());
            acc.gmv += b.getAmount();
        }
        return byRoute.values().stream()
                .sorted(Comparator.comparingLong((RouteAccumulator a) -> a.gmv).reversed())
                .limit(TOP_ROUTES_LIMIT)
                .map(a -> new AdminStatsResponse.RouteStat(a.origin, a.destination, a.tripIds.size(), a.gmv))
                .toList();
    }

    private static final class RouteAccumulator {
        final String origin;
        final String destination;
        final Set<java.util.UUID> tripIds = new HashSet<>();
        long gmv;

        RouteAccumulator(String origin, String destination) {
            this.origin = origin;
            this.destination = destination;
        }
    }
}
