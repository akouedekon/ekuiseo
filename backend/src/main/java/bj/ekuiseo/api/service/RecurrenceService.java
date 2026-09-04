package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Genere les occurrences des trajets QUOTIDIEN recurrents (regle metier n.6).
 * Un trajet "parent" (trip_type = QUOTIDIEN, recurrence_rule renseignee,
 * parent_trip_id = null) decrit le modele (heure, itineraire, prix, vehicule).
 * Cette tache genere, pour les 14 prochains jours, une occurrence (trip enfant,
 * parent_trip_id = id du parent) par jour correspondant a la regle de
 * recurrence, si elle n'existe pas deja.
 *
 * <p>Format de recurrence supporte (sous-ensemble volontairement simplifie de
 * RFC 5545) : {@code FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR}. BYDAY absent = tous
 * les jours.</p>
 */
@Service
public class RecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceService.class);
    private static final int HORIZON_DAYS = 14;

    private final TripRepository tripRepository;
    private final SearchAlertMatchService searchAlertMatchService;

    public RecurrenceService(TripRepository tripRepository, SearchAlertMatchService searchAlertMatchService) {
        this.tripRepository = tripRepository;
        this.searchAlertMatchService = searchAlertMatchService;
    }

    /** Execute chaque jour a 03h00 UTC. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void generateUpcomingOccurrences() {
        List<Trip> parents = tripRepository.findByRecurrenceRuleIsNotNullAndStatus(TripStatus.PUBLISHED).stream()
                .filter(t -> t.getParentTripId() == null)
                .toList();
        int created = 0;
        for (Trip parent : parents) {
            created += generateFor(parent);
        }
        if (created > 0) {
            log.info("Recurrence : {} occurrence(s) generee(s) pour les {} prochains jours", created, HORIZON_DAYS);
        }
    }

    int generateFor(Trip parent) {
        Set<DayOfWeek> days = parseByDay(parent.getRecurrenceRule());
        LocalTime timeOfDay = parent.getDepartureAt().atZone(ZoneOffset.UTC).toLocalTime();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int created = 0;
        for (int i = 1; i <= HORIZON_DAYS; i++) {
            LocalDate date = today.plusDays(i);
            if (!days.contains(date.getDayOfWeek())) {
                continue;
            }
            Instant occurrenceDeparture = date.atTime(timeOfDay).toInstant(ZoneOffset.UTC);
            if (tripRepository.existsByParentTripIdAndDepartureAt(parent.getId(), occurrenceDeparture)) {
                continue;
            }
            Trip occurrence = Trip.builder()
                    .driver(parent.getDriver())
                    .vehicle(parent.getVehicle())
                    .tripType(parent.getTripType())
                    .originLabel(parent.getOriginLabel())
                    .originLat(parent.getOriginLat())
                    .originLng(parent.getOriginLng())
                    .destLabel(parent.getDestLabel())
                    .destLat(parent.getDestLat())
                    .destLng(parent.getDestLng())
                    .departureAt(occurrenceDeparture)
                    .seatsTotal(parent.getSeatsTotal())
                    .seatsAvailable(parent.getSeatsTotal())
                    .pricePerSeat(parent.getPricePerSeat())
                    .instantBooking(parent.isInstantBooking())
                    .luggagePolicy(parent.getLuggagePolicy())
                    .description(parent.getDescription())
                    .status(TripStatus.PUBLISHED)
                    .parentTripId(parent.getId())
                    .build();
            occurrence = tripRepository.save(occurrence);
            searchAlertMatchService.notifyMatchingAlerts(occurrence);
            created++;
        }
        return created;
    }

    static Set<DayOfWeek> parseByDay(String rrule) {
        if (rrule == null || rrule.isBlank()) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("BYDAY")) {
                Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                Arrays.stream(kv[1].split(",")).map(String::trim).forEach(code -> {
                    DayOfWeek d = fromIcalCode(code);
                    if (d != null) days.add(d);
                });
                return days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : days;
            }
        }
        return EnumSet.allOf(DayOfWeek.class);
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
