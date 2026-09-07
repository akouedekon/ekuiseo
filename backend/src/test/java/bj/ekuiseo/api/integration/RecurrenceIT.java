package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.service.RecurrenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regle metier n.9 (14 jours glissants) sur base reelle : generation des occurrences
 * d'un modele de navette, idempotence d'un second appel (index unique
 * {@code uq_trips_parent_departure}, migration V13), plafond COUNT, copie des arrets.
 */
class RecurrenceIT extends AbstractPostgisIT {

    @Autowired
    private RecurrenceService recurrenceService;
    @Autowired
    private TripStopRepository tripStopRepository;

    private User driver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        driver = newUser("Awa", Role.USER);
        vehicle = newVehicle(driver);
    }

    @Test
    void generateFor_createsFourteenDailyOccurrences_thenSecondCallCreatesNone() {
        Trip template = template("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU");
        tripStopRepository.save(TripStop.builder().trip(template).position(1).label("Godomey")
                .lat(6.3833).lng(2.3333).priceFromOrigin(300).build());

        int created = recurrenceService.generateFor(template);

        assertThat(created).as("de demain a aujourd hui + 14 jours").isEqualTo(14);
        assertThat(tripRepository.countByParentTripId(template.getId())).isEqualTo(14);
        List<Trip> occurrences = tripRepository.findByParentTripIdAndStatusInAndDepartureAtAfter(
                template.getId(), List.of(TripStatus.PUBLISHED), Instant.now());
        assertThat(occurrences).hasSize(14);
        assertThat(occurrences).allSatisfy(o -> {
            assertThat(o.getDepartureAt().atZone(Tz.BENIN).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(o.getSeatsAvailable()).isEqualTo(3);
            assertThat(o.getRecurrenceRule()).isNull();
            assertThat(tripStopRepository.findByTripIdOrderByPosition(o.getId()))
                    .extracting(TripStop::getLabel).containsExactly("Godomey");
        });
        assertThat(tripRepository.findById(template.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.TEMPLATE);

        assertThat(recurrenceService.generateFor(template)).as("idempotent").isZero();
        assertThat(tripRepository.countByParentTripId(template.getId())).isEqualTo(14);
    }

    @Test
    void generateFor_respectsCountCap_andWeekdaysOnly() {
        Trip template = template("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=3");

        assertThat(recurrenceService.generateFor(template)).isEqualTo(3);
        assertThat(tripRepository.countByParentTripId(template.getId())).isEqualTo(3);
        List<Trip> occurrences = tripRepository.findByParentTripIdAndStatusInAndDepartureAtAfter(
                template.getId(), List.of(TripStatus.PUBLISHED), Instant.now());
        assertThat(occurrences).allSatisfy(o -> assertThat(o.getDepartureAt().atZone(Tz.BENIN).getDayOfWeek().getValue())
                .as("jamais le week-end").isLessThanOrEqualTo(5));
        assertThat(recurrenceService.generateFor(template)).isZero();
    }

    @Test
    void uniqueIndexParentDeparture_rejectsDuplicateOccurrence() {
        Trip template = template("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;COUNT=1");
        assertThat(recurrenceService.generateFor(template)).isEqualTo(1);
        Trip occurrence = tripRepository.findByParentTripIdAndStatusInAndDepartureAtAfter(
                template.getId(), List.of(TripStatus.PUBLISHED), Instant.now()).get(0);

        Trip duplicate = Trip.builder()
                .driver(driver).vehicle(vehicle).tripType(TripType.QUOTIDIEN)
                .originLabel("Abomey-Calavi").originLat(CALAVI_LAT).originLng(CALAVI_LNG)
                .destLabel("Cotonou").destLat(COTONOU_LAT).destLng(COTONOU_LNG)
                .departureAt(occurrence.getDepartureAt())
                .seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.PUBLISHED)
                .parentTripId(template.getId())
                .build();

        assertThatThrownBy(() -> tripRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tripRepository.countByParentTripId(template.getId())).isEqualTo(1);
    }

    /** Modele de navette Calavi -> Cotonou, premier depart demain a 07:00 heure du Benin. */
    private Trip template(String rrule) {
        return tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle).tripType(TripType.QUOTIDIEN)
                .originLabel("Abomey-Calavi").originLat(CALAVI_LAT).originLng(CALAVI_LNG)
                .destLabel("Cotonou").destLat(COTONOU_LAT).destLng(COTONOU_LNG)
                .departureAt(beninLocal(LocalDate.now(Tz.BENIN).plusDays(1), LocalTime.of(7, 0)))
                .seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.TEMPLATE)
                .recurrenceRule(rrule)
                .build());
    }
}
