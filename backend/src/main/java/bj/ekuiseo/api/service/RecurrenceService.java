package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Genere les occurrences des navettes QUOTIDIEN (regle metier n.9).
 *
 * <p>Un trajet <b>modele</b> ({@code status = TEMPLATE}, {@code recurrence_rule}
 * renseignee, {@code parent_trip_id = null}) decrit l itineraire, l heure locale,
 * le prix, le vehicule et les arrets. Il n est ni cherchable ni reservable. Cette
 * classe engendre, sur un horizon glissant de 14 jours, une occurrence
 * ({@code PUBLISHED}, {@code parent_trip_id} = modele) par jour de la regle, a
 * partir du jour du premier depart inclus, sans jamais depasser {@code COUNT} ni
 * {@code UNTIL} (constats F041/F042/F125/F202/F203/F415).</p>
 *
 * <p>Format de recurrence supporte (sous-ensemble de RFC 5545) :
 * {@code FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=20} ou {@code UNTIL=20261231}.
 * BYDAY absent = tous les jours. Tout est calcule dans le fuseau du Benin
 * ({@link Tz#BENIN}) : un depart lundi 00:30 a Cotonou reste un lundi.</p>
 */
@Service
public class RecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceService.class);
    static final int HORIZON_DAYS = 14;

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final SearchAlertMatchService searchAlertMatchService;

    public RecurrenceService(TripRepository tripRepository, TripStopRepository tripStopRepository,
                             SearchAlertMatchService searchAlertMatchService) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.searchAlertMatchService = searchAlertMatchService;
    }

    /** Execute chaque jour a 03h00 (heure du serveur) pour faire glisser l horizon. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void generateUpcomingOccurrences() {
        List<Trip> templates = tripRepository
                .findByRecurrenceRuleIsNotNullAndParentTripIdIsNullAndStatus(TripStatus.TEMPLATE);
        int created = 0;
        for (Trip template : templates) {
            created += generateFor(template);
        }
        if (created > 0) {
            log.info("Recurrence : {} occurrence(s) generee(s) pour les {} prochains jours", created, HORIZON_DAYS);
        }
    }

    /**
     * Engendre les occurrences manquantes du modele sur l horizon : du jour du premier
     * depart (inclus) ou d aujourd hui si le premier depart est passe, jusqu a
     * aujourd hui + 14 jours. Idempotent (index unique parent/depart).
     *
     * @return nombre d occurrences creees
     */
    @Transactional
    public int generateFor(Trip template) {
        Rule rule = parse(template.getRecurrenceRule());
        ZonedDateTime firstDeparture = template.getDepartureAt().atZone(Tz.BENIN);
        LocalTime timeOfDay = firstDeparture.toLocalTime();
        LocalDate today = LocalDate.now(Tz.BENIN);
        LocalDate start = firstDeparture.toLocalDate().isAfter(today) ? firstDeparture.toLocalDate() : today;
        LocalDate end = today.plusDays(HORIZON_DAYS);
        if (rule.until() != null && rule.until().isBefore(end)) {
            end = rule.until();
        }
        long existing = tripRepository.countByParentTripId(template.getId());
        List<TripStop> stops = tripStopRepository.findByTripIdOrderByPosition(template.getId());
        Instant now = Instant.now();
        int created = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!rule.days().contains(date.getDayOfWeek())) {
                continue;
            }
            if (rule.count() != null && existing + created >= rule.count()) {
                break;
            }
            Instant occurrenceDeparture = date.atTime(timeOfDay).atZone(Tz.BENIN).toInstant();
            if (occurrenceDeparture.isBefore(now)) {
                continue;
            }
            if (tripRepository.existsByParentTripIdAndDepartureAt(template.getId(), occurrenceDeparture)) {
                continue;
            }
            Trip occurrence = Trip.builder()
                    .driver(template.getDriver())
                    .vehicle(template.getVehicle())
                    .tripType(template.getTripType())
                    .originLabel(template.getOriginLabel())
                    .originLat(template.getOriginLat())
                    .originLng(template.getOriginLng())
                    .destLabel(template.getDestLabel())
                    .destLat(template.getDestLat())
                    .destLng(template.getDestLng())
                    .departureAt(occurrenceDeparture)
                    .seatsTotal(template.getSeatsTotal())
                    .seatsAvailable(template.getSeatsTotal())
                    .pricePerSeat(template.getPricePerSeat())
                    .instantBooking(template.isInstantBooking())
                    .luggagePolicy(template.getLuggagePolicy())
                    .description(template.getDescription())
                    .status(TripStatus.PUBLISHED)
                    .parentTripId(template.getId())
                    .build();
            try {
                occurrence = tripRepository.saveAndFlush(occurrence);
            } catch (DataIntegrityViolationException ex) {
                // Course avec la tache nocturne ou une double creation : l occurrence existe deja.
                log.debug("Occurrence deja presente pour {} a {}", template.getId(), occurrenceDeparture);
                continue;
            }
            for (TripStop stop : stops) {
                tripStopRepository.save(TripStop.builder()
                        .trip(occurrence)
                        .position(stop.getPosition())
                        .label(stop.getLabel())
                        .lat(stop.getLat())
                        .lng(stop.getLng())
                        .plannedAt(stop.getPlannedAt() == null ? null
                                : occurrenceDeparture.plus(java.time.Duration.between(template.getDepartureAt(), stop.getPlannedAt())))
                        .priceFromOrigin(stop.getPriceFromOrigin())
                        .build());
            }
            searchAlertMatchService.notifyMatchingAlerts(occurrence);
            created++;
        }
        return created;
    }

    /** Regle analysee : jours actifs, plafond d occurrences (COUNT) et date de fin (UNTIL, incluse). */
    record Rule(Set<DayOfWeek> days, Integer count, LocalDate until) {
    }

    static Rule parse(String rrule) {
        Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
        Integer count = null;
        LocalDate until = null;
        if (rrule == null || rrule.isBlank()) {
            return new Rule(days, null, null);
        }
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toUpperCase();
            String value = kv[1].trim();
            switch (key) {
                case "BYDAY" -> {
                    Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
                    Arrays.stream(value.split(",")).map(String::trim).forEach(code -> {
                        DayOfWeek d = fromIcalCode(code);
                        if (d != null) parsed.add(d);
                    });
                    if (!parsed.isEmpty()) days = parsed;
                }
                case "COUNT" -> {
                    try {
                        int n = Integer.parseInt(value);
                        if (n > 0) count = n;
                    } catch (NumberFormatException ignored) {
                        // COUNT illisible : pas de plafond
                    }
                }
                case "UNTIL" -> {
                    try {
                        String digits = value.replaceAll("[^0-9]", "");
                        if (digits.length() >= 8) {
                            until = LocalDate.parse(digits.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                        }
                    } catch (DateTimeParseException ignored) {
                        // UNTIL illisible : pas de date de fin
                    }
                }
                default -> {
                    // FREQ et autres cles ignorees (WEEKLY est le seul frequence supportee)
                }
            }
        }
        return new Rule(days, count, until);
    }

    /** Compatibilite : jours actifs seuls. */
    static Set<DayOfWeek> parseByDay(String rrule) {
        return parse(rrule).days();
    }

    private static DayOfWeek fromIcalCode(String code) {
        return switch (code.toUpperCase()) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
