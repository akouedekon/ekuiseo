package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche geospatiale de trajets ({@code TripRepository#search}, ST_DWithin sur origine
 * ET destination, contrainte de sens, fenetre de date au fuseau du Benin) contre un vrai
 * PostGIS. Le calcul de la fenetre de jour civil est reproduit ici tel que
 * {@code TripService#search} le fait ({@code date.atStartOfDay(Tz.BENIN)}).
 */
class TripSearchIT extends AbstractPostgisIT {

    @org.springframework.beans.factory.annotation.Autowired
    private bj.ekuiseo.api.repository.TripStopRepository tripStopRepository;

    private User driver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        driver = newUser("Awa", Role.USER);
        vehicle = newVehicle(driver);
    }

    @Test
    void search_findsTripsWithinRadiusOfOriginAndDestination() {
        Trip matching = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Porto-Novo", PORTO_NOVO_LAT, PORTO_NOVO_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 4, 1500);
        // Trajet non pertinent (destination tres eloignee : Parakou).
        newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Parakou", PARAKOU_LAT, PARAKOU_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 4, 5000);

        Page<Trip> results = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, PORTO_NOVO_LAT, PORTO_NOVO_LNG,
                20_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(Trip::getId).containsExactly(matching.getId());
        assertThat(results.getTotalElements()).isEqualTo(1);
    }

    /**
     * Constat F408 : Cotonou et Abomey-Calavi sont a moins de 10 km ; avec un rayon de
     * 15 km (valeur envoyee par le front) les deux disques se recouvrent et, sans
     * contrainte de sens, un trajet Calavi -> Cotonou repondrait a une recherche
     * Cotonou -> Calavi.
     */
    @Test
    void search_respectsDirection_onShortAxis() {
        Trip toCalavi = newTrip(driver, vehicle, TripType.QUOTIDIEN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Abomey-Calavi", CALAVI_LAT, CALAVI_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 3, 500);
        Trip toCotonou = newTrip(driver, vehicle, TripType.QUOTIDIEN,
                "Abomey-Calavi", CALAVI_LAT, CALAVI_LNG, "Cotonou", COTONOU_LAT, COTONOU_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 3, 500);

        Page<Trip> cotonouToCalavi = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, CALAVI_LAT, CALAVI_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> calaviToCotonou = tripRepository.search(
                CALAVI_LAT, CALAVI_LNG, COTONOU_LAT, COTONOU_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(cotonouToCalavi.getContent()).extracting(Trip::getId).containsExactly(toCalavi.getId());
        assertThat(calaviToCotonou.getContent()).extracting(Trip::getId).containsExactly(toCotonou.getId());
    }

    /**
     * Constat F415 : un depart a 00:30 heure du Benin (UTC+1) est a 23:30 UTC la veille.
     * Le jour civil demande doit etre celui du Benin : la fenetre calculee par
     * TripService le trouve, la fenetre de la veille ne le trouve pas, et une fenetre
     * naive en UTC le manquerait.
     */
    @Test
    void search_dateWindow_usesBeninCivilDay_forDepartureAtHalfPastMidnight() {
        LocalDate day = LocalDate.now(Tz.BENIN).plusDays(2);
        Instant departure = beninLocal(day, LocalTime.of(0, 30));
        assertThat(departure.atOffset(ZoneOffset.UTC).toLocalDate()).isEqualTo(day.minusDays(1));

        Trip earlyBird = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                departure, 4, 2500);

        Page<Trip> sameBeninDay = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG, 15_000, 1, null,
                day.atStartOfDay(Tz.BENIN).toInstant(), day.plusDays(1).atStartOfDay(Tz.BENIN).toInstant(),
                Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> previousBeninDay = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG, 15_000, 1, null,
                day.minusDays(1).atStartOfDay(Tz.BENIN).toInstant(), day.atStartOfDay(Tz.BENIN).toInstant(),
                Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> naiveUtcDay = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG, 15_000, 1, null,
                day.atStartOfDay(ZoneOffset.UTC).toInstant(), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(sameBeninDay.getContent()).extracting(Trip::getId).containsExactly(earlyBird.getId());
        assertThat(previousBeninDay.getContent()).isEmpty();
        assertThat(naiveUtcDay.getContent()).as("une fenetre UTC manquerait ce depart : c est le bug F415").isEmpty();
    }

    @Test
    void search_excludesDepartedTrips_andFiltersByTypeAndSeats() {
        newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                Instant.now().minus(1, ChronoUnit.HOURS), 4, 2500); // deja parti, encore PUBLISHED
        Trip upcomingDaily = newTrip(driver, vehicle, TripType.QUOTIDIEN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                Instant.now().plus(2, ChronoUnit.HOURS), 2, 2500);
        Trip upcomingInterurban = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Bohicon", BOHICON_LAT, BOHICON_LNG,
                Instant.now().plus(3, ChronoUnit.HOURS), 4, 2500);

        Page<Trip> all = tripRepository.search(COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> dailyOnly = tripRepository.search(COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG,
                15_000, 1, TripType.QUOTIDIEN.name(), null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> threeSeats = tripRepository.search(COTONOU_LAT, COTONOU_LNG, BOHICON_LAT, BOHICON_LNG,
                15_000, 3, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(all.getContent()).extracting(Trip::getId)
                .containsExactlyInAnyOrder(upcomingDaily.getId(), upcomingInterurban.getId());
        assertThat(dailyOnly.getContent()).extracting(Trip::getId).containsExactly(upcomingDaily.getId());
        assertThat(threeSeats.getContent()).extracting(Trip::getId).containsExactly(upcomingInterurban.getId());
    }

    @Test
    void search_excludesTripsOfSuspendedDrivers() {
        User suspended = newUser("Suspendu", Role.USER, bj.ekuiseo.api.domain.enums.UserStatus.SUSPENDED);
        Vehicle suspendedVehicle = newVehicle(suspended);
        newTrip(suspended, suspendedVehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Porto-Novo", PORTO_NOVO_LAT, PORTO_NOVO_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 4, 1500);

        Page<Trip> results = tripRepository.search(COTONOU_LAT, COTONOU_LNG, PORTO_NOVO_LAT, PORTO_NOVO_LNG,
                20_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(results.getContent()).isEmpty();
    }
    /**
     * Constats F115/F409 : un trajet Cotonou -> Parakou qui marque l arret a Bohicon doit
     * repondre a une recherche Bohicon -> Parakou (montee a l arret, descente a la
     * destination) mais pas a une recherche Parakou -> Bohicon (sens) ni Bohicon -> Cotonou
     * (descente avant la montee).
     */
    @Test
    void search_matchesIntermediateStopAsPickup() {
        Trip viaBohicon = newTrip(driver, vehicle, TripType.INTERURBAIN,
                "Cotonou", COTONOU_LAT, COTONOU_LNG, "Parakou", PARAKOU_LAT, PARAKOU_LNG,
                Instant.now().plus(1, ChronoUnit.DAYS), 4, 6000);
        tripStopRepository.save(bj.ekuiseo.api.domain.TripStop.builder().trip(viaBohicon).position(1).label("Bohicon")
                .lat(BOHICON_LAT).lng(BOHICON_LNG).priceFromOrigin(2500L).build());

        Page<Trip> bohiconToParakou = tripRepository.search(BOHICON_LAT, BOHICON_LNG, PARAKOU_LAT, PARAKOU_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> parakouToBohicon = tripRepository.search(PARAKOU_LAT, PARAKOU_LNG, BOHICON_LAT, BOHICON_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));
        Page<Trip> bohiconToCotonou = tripRepository.search(BOHICON_LAT, BOHICON_LNG, COTONOU_LAT, COTONOU_LNG,
                15_000, 1, null, null, null, Instant.now(), null, null, null, false, PageRequest.of(0, 10));

        assertThat(bohiconToParakou.getContent()).extracting(Trip::getId).containsExactly(viaBohicon.getId());
        assertThat(parakouToBohicon.getContent()).isEmpty();
        assertThat(bohiconToCotonou.getContent()).isEmpty();
    }
}
