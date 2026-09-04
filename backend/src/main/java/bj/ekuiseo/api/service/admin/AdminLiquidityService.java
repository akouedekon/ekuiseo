package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.Headline;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.ModeFill;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.NorthStar;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.Period;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.RouteFill;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.ShortageRoute;
import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse.WeekSeats;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.SearchEventRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Indicateurs de liquidite du back-office (GET /api/v1/admin/stats/liquidity?days=N,
 * voir AdminLiquidityResponse pour la definition de chaque chiffre).
 *
 * <p>Un marche biface meurt par manque de liquidite bien avant de manquer de
 * volume : un passager qui ne trouve rien ne revient pas, un conducteur sans
 * passager ne republie pas. Ce service met ces deux courbes cote a cote, sur la
 * periode demandee et sur la precedente de meme duree, pour que chaque chiffre
 * s'interprete par sa variation.</p>
 *
 * <p>Tout est calcule par agregation SQL (requetes natives des depots) : aucune
 * liste de trajets, de reservations ou de recherches n'est chargee en memoire,
 * quelle que soit la periode.</p>
 */
@Service
public class AdminLiquidityService {

    /** Seuil de viabilite etabli dans l'etude de marche : en dessous, le projet paie l'hebergement, pas un salaire. */
    public static final long MONTHLY_TARGET_SEATS = 2_000;

    /**
     * Statuts pour lesquels la place a reellement ete vendue. Un NO_SHOW est une place
     * vendue (acompte encaisse, place bloquee), une annulation ne l'est pas.
     */
    static final List<BookingStatus> SOLD_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    private static final List<String> SOLD_STATUS_NAMES = SOLD_STATUSES.stream().map(Enum::name).toList();

    static final int MAX_DAYS = 365;
    private static final int ROUTE_LIMIT = 10;
    private static final int SHORTAGE_LIMIT = 10;
    private static final double DAYS_PER_MONTH = 30.0;

    private final SearchEventRepository searchEventRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    /** Constructeur retenu par Spring (l autre, avec horloge, ne sert qu aux tests). */
    @Autowired
    public AdminLiquidityService(SearchEventRepository searchEventRepository, TripRepository tripRepository,
                                 BookingRepository bookingRepository) {
        this(searchEventRepository, tripRepository, bookingRepository, Clock.systemUTC());
    }

    /** Horloge injectable pour les tests (fenetres de periode deterministes). */
    AdminLiquidityService(SearchEventRepository searchEventRepository, TripRepository tripRepository,
                          BookingRepository bookingRepository, Clock clock) {
        this.searchEventRepository = searchEventRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminLiquidityResponse compute(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("Le parametre days doit etre compris entre 1 et " + MAX_DAYS);
        }
        Instant now = Instant.now(clock);
        Instant from = now.minus(days, ChronoUnit.DAYS);
        Instant previousFrom = from.minus(days, ChronoUnit.DAYS);

        List<ModeFill> fillByMode = fillByMode(from, now);
        Headline current = headline(from, now, fillByMode);
        Headline previous = headline(previousFrom, from, fillByMode(previousFrom, from));

        return new AdminLiquidityResponse(
                new Period(days, from, now),
                northStar(days, from, now, previousFrom),
                current,
                previous,
                fillByMode,
                fillByRoute(from, now),
                shortageRoutes(from, now));
    }

    private NorthStar northStar(int days, Instant from, Instant to, Instant previousFrom) {
        long confirmedSeats = bookingRepository.sumSeatsBetween(from, to, SOLD_STATUSES);
        long previousSeats = bookingRepository.sumSeatsBetween(previousFrom, from, SOLD_STATUSES);
        double monthlyPace = Math.round(confirmedSeats * DAYS_PER_MONTH / days * 10.0) / 10.0;
        double progress = Math.round(monthlyPace / MONTHLY_TARGET_SEATS * 1000.0) / 10.0;
        List<WeekSeats> weekly = bookingRepository.getSeatsByWeek(from, to, SOLD_STATUS_NAMES).stream()
                .map(w -> new WeekSeats(w.getWeekStart(), w.getSeats()))
                .toList();
        return new NorthStar(confirmedSeats, previousSeats, monthlyPace, MONTHLY_TARGET_SEATS, progress, weekly);
    }

    private Headline headline(Instant from, Instant to, List<ModeFill> modes) {
        SearchEventRepository.FunnelStats funnel = searchEventRepository.getFunnelStats(from, to);
        long searches = funnel != null ? funnel.getTotal() : 0;
        long withResults = funnel != null ? funnel.getWithResults() : 0;
        long byUsers = funnel != null ? funnel.getByUsers() : 0;
        long converted = funnel != null ? funnel.getConverted() : 0;

        long trips = 0, seatsPublished = 0, seatsBooked = 0, orphans = 0;
        for (ModeFill m : modes) {
            trips += m.trips();
            seatsPublished += m.seatsPublished();
            seatsBooked += m.seatsBooked();
            orphans += m.orphanTrips();
        }

        TripRepository.FirstBookingDelayStats delay =
                tripRepository.getFirstBookingDelayStats(from, to, SOLD_STATUS_NAMES);
        long sampleSize = delay != null ? delay.getSampleSize() : 0;
        Double medianHours = delay != null && sampleSize > 0 && delay.getMedianHours() != null
                ? Math.round(delay.getMedianHours() * 10.0) / 10.0
                : null;

        return new Headline(
                searches, withResults, percent(withResults, searches),
                byUsers, converted, percent(converted, byUsers),
                trips, seatsPublished, seatsBooked, percent(seatsBooked, seatsPublished),
                orphans, percent(orphans, trips),
                medianHours, sampleSize);
    }

    private List<ModeFill> fillByMode(Instant from, Instant to) {
        return tripRepository.getFillStatsByMode(from, to, SOLD_STATUS_NAMES).stream()
                .map(m -> new ModeFill(TripType.valueOf(m.getTripType()), m.getTrips(),
                        m.getSeatsPublished(), m.getSeatsBooked(), percent(m.getSeatsBooked(), m.getSeatsPublished()),
                        m.getOrphanTrips(), percent(m.getOrphanTrips(), m.getTrips())))
                .toList();
    }

    private List<RouteFill> fillByRoute(Instant from, Instant to) {
        return tripRepository.getFillStatsByRoute(from, to, SOLD_STATUS_NAMES, ROUTE_LIMIT).stream()
                .map(r -> new RouteFill(r.getOrigin(), r.getDestination(), TripType.valueOf(r.getTripType()),
                        r.getTrips(), r.getSeatsPublished(), r.getSeatsBooked(),
                        percent(r.getSeatsBooked(), r.getSeatsPublished()), r.getOrphanTrips()))
                .toList();
    }

    private List<ShortageRoute> shortageRoutes(Instant from, Instant to) {
        return searchEventRepository.findShortageRoutes(from, to, SHORTAGE_LIMIT).stream()
                .map(s -> new ShortageRoute(s.getOrigin(), s.getDestination(), s.getSearches(),
                        s.getWithoutResults(), toInstant(s.getLastSearchedEpoch())))
                .toList();
    }

    /** Pourcentage arrondi au dixieme ; 0 quand le denominateur est nul (pas de division, pas de NaN). */
    static double percent(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return Math.round((double) numerator / denominator * 1000.0) / 10.0;
    }

    private static Instant toInstant(Double epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0));
    }

    // ------------------------------------------------------------------ CSV

    /**
     * Export tableur des memes chiffres. Le fondateur travaille dans un tableur en
     * francais : separateur ';', decimales a la virgule, BOM UTF-8 pour qu'Excel
     * reconnaisse l'encodage. Plusieurs blocs separes par une ligne vide, chacun
     * avec son en-tete.
     */
    public String toCsv(AdminLiquidityResponse r) {
        StringBuilder sb = new StringBuilder("﻿");
        Headline c = r.current();
        Headline p = r.previous();
        row(sb, "indicateur", "periode_courante", "periode_precedente");
        row(sb, "jours", r.period().days(), r.period().days());
        row(sb, "debut_periode", r.period().from(), r.period().from().minus(r.period().days(), ChronoUnit.DAYS));
        row(sb, "places_confirmees", r.northStar().confirmedSeats(), r.northStar().previousConfirmedSeats());
        row(sb, "rythme_mensuel_places", r.northStar().monthlyPace(), "");
        row(sb, "seuil_mensuel_places", r.northStar().monthlyTarget(), "");
        row(sb, "recherches", c.searches(), p.searches());
        row(sb, "recherches_avec_resultat", c.searchesWithResults(), p.searchesWithResults());
        row(sb, "taux_recherche_aboutie_pct", c.searchSuccessRate(), p.searchSuccessRate());
        row(sb, "recherches_utilisateurs_connectes", c.searchesByUsers(), p.searchesByUsers());
        row(sb, "recherches_suivies_reservation_24h", c.searchesConverted(), p.searchesConverted());
        row(sb, "taux_recherche_vers_reservation_pct", c.searchToBookingRate(), p.searchToBookingRate());
        row(sb, "trajets_partis", c.trips(), p.trips());
        row(sb, "places_publiees", c.seatsPublished(), p.seatsPublished());
        row(sb, "places_reservees", c.seatsBooked(), p.seatsBooked());
        row(sb, "taux_remplissage_pct", c.fillRate(), p.fillRate());
        row(sb, "trajets_orphelins", c.orphanTrips(), p.orphanTrips());
        row(sb, "taux_trajets_orphelins_pct", c.orphanRate(), p.orphanRate());
        row(sb, "delai_median_premiere_reservation_h", c.medianHoursToFirstBooking(), p.medianHoursToFirstBooking());
        row(sb, "trajets_echantillon_delai", c.firstBookingSampleSize(), p.firstBookingSampleSize());

        sb.append("\r\n");
        row(sb, "mode", "trajets", "places_publiees", "places_reservees", "taux_remplissage_pct",
                "trajets_orphelins", "taux_orphelins_pct");
        for (ModeFill m : r.fillByMode()) {
            row(sb, m.tripType(), m.trips(), m.seatsPublished(), m.seatsBooked(), m.fillRate(),
                    m.orphanTrips(), m.orphanRate());
        }

        sb.append("\r\n");
        row(sb, "origine", "destination", "mode", "trajets", "places_publiees", "places_reservees",
                "taux_remplissage_pct", "trajets_orphelins");
        for (RouteFill f : r.fillByRoute()) {
            row(sb, f.origin(), f.destination(), f.tripType(), f.trips(), f.seatsPublished(), f.seatsBooked(),
                    f.fillRate(), f.orphanTrips());
        }

        sb.append("\r\n");
        row(sb, "origine", "destination", "recherches", "recherches_sans_resultat", "derniere_recherche");
        for (ShortageRoute s : r.shortageRoutes()) {
            row(sb, s.origin(), s.destination(), s.searches(), s.searchesWithoutResults(), s.lastSearchedAt());
        }

        sb.append("\r\n");
        row(sb, "semaine_du", "places_confirmees");
        for (WeekSeats w : r.northStar().weekly()) {
            row(sb, w.weekStart(), w.seats());
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, Object... cells) {
        List<String> formatted = new ArrayList<>(cells.length);
        for (Object cell : cells) {
            formatted.add(csvCell(cell));
        }
        sb.append(String.join(";", formatted)).append("\r\n");
    }

    private static String csvCell(Object value) {
        if (value == null) return "";
        String text;
        if (value instanceof Double d) {
            text = String.format(Locale.FRANCE, "%.1f", d);
        } else {
            text = value.toString();
        }
        if (text.contains(";") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
