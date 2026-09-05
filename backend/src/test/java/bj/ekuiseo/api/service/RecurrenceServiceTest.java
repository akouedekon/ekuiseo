package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generation des occurrences d une navette quotidienne (lot 1.3) : COUNT respecte,
 * BYDAY dans le fuseau du Benin, arrets copies, premier jour inclus, idempotence.
 */
class RecurrenceServiceTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final TripStopRepository tripStopRepository = mock(TripStopRepository.class);
    private final SearchAlertMatchService alerts = mock(SearchAlertMatchService.class);
    private final RecurrenceService service = new RecurrenceService(tripRepository, tripStopRepository, alerts);

    @Test
    void parse_readsByDayCountAndUntil() {
        RecurrenceService.Rule rule = RecurrenceService.parse("FREQ=WEEKLY;COUNT=10;BYDAY=MO,WE,FR;UNTIL=20261231T000000Z");
        assertThat(rule.days()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        assertThat(rule.count()).isEqualTo(10);
        assertThat(rule.until()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void parse_defaultsToEveryDay_withoutByDay() {
        RecurrenceService.Rule rule = RecurrenceService.parse("FREQ=WEEKLY");
        assertThat(rule.days()).hasSize(7);
        assertThat(rule.count()).isNull();
        assertThat(rule.until()).isNull();
    }

    @Test
    void generateFor_honoursCount_includesFirstDay_andCopiesStops() {
        Trip template = template("FREQ=WEEKLY;COUNT=3", LocalDate.now(Tz.BENIN).plusDays(1), LocalTime.of(7, 30));
        when(tripRepository.countByParentTripId(template.getId())).thenReturn(0L);
        when(tripRepository.existsByParentTripIdAndDepartureAt(any(), any())).thenReturn(false);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(tripStopRepository.findByTripIdOrderByPosition(template.getId())).thenReturn(List.of(
                TripStop.builder().position(1).label("Godomey").lat(6.37).lng(2.34).priceFromOrigin(300L).build()));

        int created = service.generateFor(template);

        assertThat(created).isEqualTo(3);
        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository, times(3)).saveAndFlush(captor.capture());
        List<Trip> occurrences = captor.getAllValues();
        // Premier jour inclus, puis les jours suivants, a 07:30 heure du Benin.
        assertThat(occurrences.get(0).getDepartureAt().atZone(Tz.BENIN).toLocalDate()).isEqualTo(LocalDate.now(Tz.BENIN).plusDays(1));
        assertThat(occurrences.get(2).getDepartureAt().atZone(Tz.BENIN).toLocalDate()).isEqualTo(LocalDate.now(Tz.BENIN).plusDays(3));
        occurrences.forEach(o -> {
            assertThat(o.getStatus()).isEqualTo(TripStatus.PUBLISHED);
            assertThat(o.getParentTripId()).isEqualTo(template.getId());
            assertThat(o.getDepartureAt().atZone(Tz.BENIN).toLocalTime()).isEqualTo(LocalTime.of(7, 30));
            assertThat(o.getSeatsAvailable()).isEqualTo(4);
        });
        verify(tripStopRepository, times(3)).save(any(TripStop.class));
        verify(alerts, times(3)).notifyMatchingAlerts(any(Trip.class));
    }

    @Test
    void generateFor_countsExistingOccurrences_towardsCount() {
        Trip template = template("FREQ=WEEKLY;COUNT=5", LocalDate.now(Tz.BENIN).plusDays(1), LocalTime.of(7, 0));
        when(tripRepository.countByParentTripId(template.getId())).thenReturn(5L);

        assertThat(service.generateFor(template)).isZero();
        verify(tripRepository, times(0)).saveAndFlush(any(Trip.class));
    }

    @Test
    void generateFor_keepsWeekdayInBeninZone_forEarlyMorningDeparture() {
        // Un lundi 00:30 a Cotonou est un dimanche 23:30 UTC : la regle BYDAY=MO doit
        // le retenir, et le placer un lundi (constat F415).
        LocalDate nextMonday = LocalDate.now(Tz.BENIN).plusDays(1);
        while (nextMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            nextMonday = nextMonday.plusDays(1);
        }
        Trip template = template("FREQ=WEEKLY;BYDAY=MO;COUNT=1", nextMonday, LocalTime.of(0, 30));
        when(tripRepository.countByParentTripId(template.getId())).thenReturn(0L);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        int created = service.generateFor(template);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository, atLeastOnce()).saveAndFlush(captor.capture());
        ZonedDateTime local = captor.getValue().getDepartureAt().atZone(Tz.BENIN);
        assertThat(local.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(0, 30));
    }

    @Test
    void generateFor_skipsExistingDepartures() {
        Trip template = template("FREQ=WEEKLY;COUNT=2", LocalDate.now(Tz.BENIN).plusDays(1), LocalTime.of(8, 0));
        when(tripRepository.countByParentTripId(template.getId())).thenReturn(1L);
        when(tripRepository.existsByParentTripIdAndDepartureAt(eq(template.getId()), any())).thenReturn(true, false);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1 existante + 1 a creer = COUNT 2 ; la premiere date existe deja et est sautee.
        assertThat(service.generateFor(template)).isEqualTo(1);
    }

    private static Trip template(String rule, LocalDate firstDay, LocalTime time) {
        User driver = User.builder().id(UUID.randomUUID()).phone("+2290197000001").build();
        Vehicle vehicle = Vehicle.builder().id(UUID.randomUUID()).owner(driver).seats(4).build();
        return Trip.builder()
                .id(UUID.randomUUID())
                .driver(driver)
                .vehicle(vehicle)
                .tripType(TripType.QUOTIDIEN)
                .originLabel("Abomey-Calavi").originLat(6.45).originLng(2.35)
                .destLabel("Cotonou").destLat(6.37).destLng(2.42)
                .departureAt(firstDay.atTime(time).atZone(Tz.BENIN).toInstant())
                .seatsTotal(4).seatsAvailable(4)
                .pricePerSeat(500)
                .status(TripStatus.TEMPLATE)
                .recurrenceRule(rule)
                .build();
    }
}
