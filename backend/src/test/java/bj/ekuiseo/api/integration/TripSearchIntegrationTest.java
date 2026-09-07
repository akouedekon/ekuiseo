package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'integration de la recherche geospatiale de trajets (ST_DWithin sur
 * origine ET destination, arrets intermediaires inclus) contre une vraie base PostGIS
 * demarree via Testcontainers.
 *
 * <p><b>DESACTIVE PAR DEFAUT</b> : cet environnement de build n'a pas acces au
 * registre Docker Hub (la politique reseau de la session bloque
 * registry-1.docker.io ainsi que Maven Central ; verifie via
 * {@code docker pull hello-world} -> "403 Forbidden"), donc l'image
 * postgis/postgis ne peut pas etre telechargee ici. Retirez l'annotation
 * {@code @Disabled} pour executer ce test dans un environnement disposant
 * d'un acces Docker complet (poste de developpement, CI classique, etc.).</p>
 */
@Testcontainers
@SpringBootTest
@Disabled("Necessite Docker + acces au registre pour l'image postgis/postgis (indisponible dans ce sandbox)")
class TripSearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgis = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ekuiseo")
            .withUsername("ekuiseo")
            .withPassword("ekuiseo");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
    }

    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripStopRepository tripStopRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VehicleRepository vehicleRepository;

    private User driver;
    private Vehicle vehicle;

    // Cotonou (centre-ville) et Porto-Novo, utilises comme points de reference.
    private static final double COTONOU_LAT = 6.3703;
    private static final double COTONOU_LNG = 2.3912;
    private static final double PORTO_NOVO_LAT = 6.4969;
    private static final double PORTO_NOVO_LNG = 2.6289;
    // Abomey-Calavi (referentiel V3), a 9,6 km de Cotonou : l axe quotidien du modele economique.
    private static final double CALAVI_LAT = 6.4489;
    private static final double CALAVI_LNG = 2.3556;
    // Bohicon et Parakou (referentiel V3), sur l axe nord.
    private static final double BOHICON_LAT = 7.1783;
    private static final double BOHICON_LNG = 2.0667;
    private static final double PARAKOU_LAT = 9.3372;
    private static final double PARAKOU_LNG = 2.6303;

    @BeforeEach
    void setUp() {
        driver = userRepository.save(User.builder()
                .phone("+2290197000001")
                .firstName("Awa")
                .lastName("Conductrice")
                .passwordHash("x")
                .status(UserStatus.ACTIVE)
                .build());
        vehicle = vehicleRepository.save(Vehicle.builder()
                .owner(driver)
                .brand("Toyota")
                .model("Corolla")
                .plate("AB-1234-BJ")
                .seats(4)
                .comfortLevel(ComfortLevel.COMFORT)
                .build());
    }

    private Page<Trip> search(double oLat, double oLng, double dLat, double dLng, double radiusMeters) {
        return tripRepository.search(oLat, oLng, dLat, dLng, radiusMeters, 1, null, null, null, Instant.now(),
                "DEPARTURE", null, null, false, PageRequest.of(0, 10));
    }

    @Test
    void search_findsTripsWithinRadiusOfOriginAndDestination() {
        Trip matching = tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(TripType.INTERURBAIN)
                .originLabel("Cotonou").originLat(COTONOU_LAT).originLng(COTONOU_LNG)
                .destLabel("Porto-Novo").destLat(PORTO_NOVO_LAT).destLng(PORTO_NOVO_LNG)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(4).seatsAvailable(4).pricePerSeat(1500)
                .status(TripStatus.PUBLISHED)
                .build());

        // Trajet non pertinent (destination tres eloignee : Parakou).
        tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(TripType.INTERURBAIN)
                .originLabel("Cotonou").originLat(COTONOU_LAT).originLng(COTONOU_LNG)
                .destLabel("Parakou").destLat(PARAKOU_LAT).destLng(PARAKOU_LNG)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(4).seatsAvailable(4).pricePerSeat(5000)
                .status(TripStatus.PUBLISHED)
                .build());

        Page<Trip> results = search(COTONOU_LAT, COTONOU_LNG, PORTO_NOVO_LAT, PORTO_NOVO_LNG, 20_000);

        assertThat(results.getContent()).extracting(Trip::getId).containsExactly(matching.getId());
    }

    /**
     * Constat F408 : Cotonou et Abomey-Calavi sont a moins de 10 km ; avec un rayon de
     * 15 km (valeur envoyee par le front) les deux disques se recouvrent et, sans
     * contrainte de sens, un trajet Calavi -> Cotonou repondrait a une recherche
     * Cotonou -> Calavi. La requete exige desormais que chaque extremite du trajet soit
     * plus proche de l extremite cherchee correspondante que de l autre.
     */
    @Test
    void search_respectsDirection_onShortAxis() {
        Trip toCalavi = tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(TripType.QUOTIDIEN)
                .originLabel("Cotonou").originLat(COTONOU_LAT).originLng(COTONOU_LNG)
                .destLabel("Abomey-Calavi").destLat(CALAVI_LAT).destLng(CALAVI_LNG)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.PUBLISHED)
                .build());
        Trip toCotonou = tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(TripType.QUOTIDIEN)
                .originLabel("Abomey-Calavi").originLat(CALAVI_LAT).originLng(CALAVI_LNG)
                .destLabel("Cotonou").destLat(COTONOU_LAT).destLng(COTONOU_LNG)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(3).seatsAvailable(3).pricePerSeat(500)
                .status(TripStatus.PUBLISHED)
                .build());

        Page<Trip> cotonouToCalavi = search(COTONOU_LAT, COTONOU_LNG, CALAVI_LAT, CALAVI_LNG, 15_000);
        Page<Trip> calaviToCotonou = search(CALAVI_LAT, CALAVI_LNG, COTONOU_LAT, COTONOU_LNG, 15_000);

        assertThat(cotonouToCalavi.getContent()).extracting(Trip::getId).containsExactly(toCalavi.getId());
        assertThat(calaviToCotonou.getContent()).extracting(Trip::getId).containsExactly(toCotonou.getId());
    }

    /**
     * Constats F115/F409 : un trajet Cotonou -> Parakou qui marque l arret a Bohicon doit
     * repondre a une recherche Bohicon -> Parakou (montee a l arret, descente a la
     * destination) mais pas a une recherche Parakou -> Bohicon (sens) ni Bohicon -> Cotonou
     * (descente avant la montee).
     */
    @Test
    void search_matchesIntermediateStopAsPickup() {
        Trip viaBohicon = tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(TripType.INTERURBAIN)
                .originLabel("Cotonou").originLat(COTONOU_LAT).originLng(COTONOU_LNG)
                .destLabel("Parakou").destLat(PARAKOU_LAT).destLng(PARAKOU_LNG)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(4).seatsAvailable(4).pricePerSeat(6000)
                .status(TripStatus.PUBLISHED)
                .build());
        tripStopRepository.save(TripStop.builder().trip(viaBohicon).position(1).label("Bohicon")
                .lat(BOHICON_LAT).lng(BOHICON_LNG).priceFromOrigin(2500).build());

        Page<Trip> bohiconToParakou = search(BOHICON_LAT, BOHICON_LNG, PARAKOU_LAT, PARAKOU_LNG, 15_000);
        Page<Trip> parakouToBohicon = search(PARAKOU_LAT, PARAKOU_LNG, BOHICON_LAT, BOHICON_LNG, 15_000);
        Page<Trip> bohiconToCotonou = search(BOHICON_LAT, BOHICON_LNG, COTONOU_LAT, COTONOU_LNG, 15_000);

        assertThat(bohiconToParakou.getContent()).extracting(Trip::getId).containsExactly(viaBohicon.getId());
        assertThat(parakouToBohicon.getContent()).isEmpty();
        assertThat(bohiconToCotonou.getContent()).isEmpty();
    }
}
