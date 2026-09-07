package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse.OperatorFailure;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse.PaymentMethodShare;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse.Scalars;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
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
 * Indicateurs de retention et de paiement du back-office (GET /api/v1/admin/stats/retention?days=N,
 * point n.14 de l audit ; definitions dans AdminRetentionResponse).
 *
 * <p>Meme modele que {@link AdminLiquidityService} : tout est calcule par agregation SQL
 * (cohortes hebdomadaires comprises, requetes natives des depots), aucune collection de
 * conducteurs, passagers, reservations ou paiements n est chargee en memoire, quelle que
 * soit la periode. Les taux sont des fractions 0..1, nulles quand le denominateur est nul.</p>
 */
@Service
public class AdminRetentionService {

    /** Statuts pour lesquels la place a reellement ete vendue (memes que AdminLiquidityService). */
    static final List<BookingStatus> SOLD_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    private static final List<String> SOLD_STATUS_NAMES = SOLD_STATUSES.stream().map(Enum::name).toList();

    static final int MAX_DAYS = 365;

    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    /** Constructeur retenu par Spring (l autre, avec horloge, ne sert qu aux tests). */
    @Autowired
    public AdminRetentionService(TripRepository tripRepository, BookingRepository bookingRepository,
                                 PaymentRepository paymentRepository) {
        this(tripRepository, bookingRepository, paymentRepository, Clock.systemUTC());
    }

    /** Horloge injectable pour les tests (fenetres de periode deterministes). */
    AdminRetentionService(TripRepository tripRepository, BookingRepository bookingRepository,
                          PaymentRepository paymentRepository, Clock clock) {
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminRetentionResponse compute(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("Le parametre days doit etre compris entre 1 et " + MAX_DAYS);
        }
        Instant now = Instant.now(clock);
        Instant from = now.minus(days, ChronoUnit.DAYS);
        Instant previousFrom = from.minus(days, ChronoUnit.DAYS);

        Scalars current = scalars(from, now, now);
        Scalars previous = scalars(previousFrom, from, now);

        List<OperatorFailure> failures = paymentRepository.getFailuresByOperator(from, now).stream()
                .map(f -> new OperatorFailure(f.getOperator(), f.getAttempts(), f.getFailures()))
                .toList();
        List<PaymentMethodShare> methods = bookingRepository.getPaymentMethodShare(from, now, SOLD_STATUS_NAMES).stream()
                .map(m -> new PaymentMethodShare(PaymentMethod.valueOf(m.getMethod()), m.getCount(), m.getAmountFcfa()))
                .toList();

        return new AdminRetentionResponse(days,
                current.driverRetentionW1(), current.driverRetentionW4(), current.passengerRetention30d(),
                current.dailyModeShare(), current.activeRecurringTemplates(), current.avgFilledSeatsPerOccurrence(),
                current.bookingToDepositRate(), current.expiredBookingShare(),
                failures, methods,
                current.averageBasketFcfa(), current.seatsPerBooking(),
                previous);
    }

    /** Les scalaires d une periode [from, to) ; {@code now} borne l observabilite des cohortes. */
    private Scalars scalars(Instant from, Instant to, Instant now) {
        TripRepository.DriverRetentionStats drivers = tripRepository.getDriverRetentionStats(from, to, now);
        BookingRepository.PassengerRetentionStats passengers =
                bookingRepository.getPassengerRetentionStats(from, to, SOLD_STATUS_NAMES, now);
        BookingRepository.SoldBookingStats sold = bookingRepository.getSoldBookingStats(from, to, SOLD_STATUS_NAMES);
        TripRepository.RecurringStats recurring = tripRepository.getRecurringStats(from, to, SOLD_STATUS_NAMES);
        BookingRepository.MomoConversionStats momo = bookingRepository.getMomoConversionStats(from, to);

        long soldBookings = sold == null ? 0 : sold.getSoldBookings();
        long momoBookings = momo == null ? 0 : momo.getMomoBookings();
        return new Scalars(
                drivers == null ? null : ratio(drivers.getW1Retained(), drivers.getW1Cohort()),
                drivers == null ? null : ratio(drivers.getW4Retained(), drivers.getW4Cohort()),
                passengers == null ? null : ratio(passengers.getRetained(), passengers.getCohortSize()),
                sold == null ? null : ratio(sold.getDailyBookings(), soldBookings),
                recurring == null ? 0 : recurring.getActiveTemplates(),
                recurring == null || recurring.getOccurrences() == 0 ? null : round(recurring.getAvgFilledSeats(), 2),
                momo == null ? null : ratio(momo.getPaidBookings(), momoBookings),
                momo == null ? null : ratio(momo.getExpiredBookings(), momoBookings),
                sold == null || soldBookings == 0 ? null : round(sold.getAverageBasket(), 1),
                sold == null || soldBookings == 0 ? null : round(sold.getSeatsPerBooking(), 2));
    }

    /** Fraction arrondie a 4 decimales ; null quand le denominateur est nul (jamais 0 ni NaN). */
    static Double ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return Math.round((double) numerator / denominator * 10_000.0) / 10_000.0;
    }

    static Double round(Double value, int decimals) {
        if (value == null) return null;
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    // ------------------------------------------------------------------ CSV

    /**
     * Export tableur des memes chiffres (meme forme que AdminLiquidityService#toCsv) :
     * separateur ';', decimales a la virgule, BOM UTF-8. Les taux restent des fractions
     * (0,325 = 32,5 %), a formater dans le tableur.
     */
    public String toCsv(AdminRetentionResponse r) {
        StringBuilder sb = new StringBuilder("﻿");
        Scalars p = r.previous();
        row(sb, "indicateur", "periode_courante", "periode_precedente");
        row(sb, "jours", r.days(), r.days());
        row(sb, "retention_conducteur_w1", r.driverRetentionW1(), p.driverRetentionW1());
        row(sb, "retention_conducteur_w4", r.driverRetentionW4(), p.driverRetentionW4());
        row(sb, "retention_passager_30j", r.passengerRetention30d(), p.passengerRetention30d());
        row(sb, "part_mode_quotidien", r.dailyModeShare(), p.dailyModeShare());
        row(sb, "navettes_actives", r.activeRecurringTemplates(), p.activeRecurringTemplates());
        row(sb, "places_vendues_par_occurrence", r.avgFilledSeatsPerOccurrence(), p.avgFilledSeatsPerOccurrence());
        row(sb, "taux_reservation_vers_acompte", r.bookingToDepositRate(), p.bookingToDepositRate());
        row(sb, "part_reservations_expirees", r.expiredBookingShare(), p.expiredBookingShare());
        row(sb, "panier_moyen_fcfa", r.averageBasketFcfa(), p.averageBasketFcfa());
        row(sb, "places_par_reservation", r.seatsPerBooking(), p.seatsPerBooking());

        sb.append("\r\n");
        row(sb, "operateur", "tentatives", "echecs", "taux_echec");
        for (OperatorFailure f : r.kkiapayFailureByOperator()) {
            row(sb, f.operator(), f.attempts(), f.failures(), ratio(f.failures(), f.attempts()));
        }

        sb.append("\r\n");
        row(sb, "mode_paiement", "reservations", "volume_fcfa");
        for (PaymentMethodShare m : r.paymentMethodShare()) {
            row(sb, m.method(), m.count(), m.amountFcfa());
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
            text = String.format(Locale.FRANCE, "%.4f", d).replaceAll("0+$", "").replaceAll(",$", ",0");
        } else {
            text = value.toString();
        }
        if (text.contains(";") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
