package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.TripRepository;
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
 * origine ET destination) contre une vraie base PostGIS demarree via
 * Testcontainers.
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

    @BeforeEach
    void setUp() {
        driver = userRepository.save(User.builder()
                .phone("+22997000001")
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
                .destLabel("Parakou").destLat(9.3372).destLng(2.6303)
                .departureAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatsTotal(4).seatsAvailable(4).pricePerSeat(5000)
                .status(TripStatus.PUBLISHED)
                .build());

        Page<Trip> results = tripRepository.search(
                COTONOU_LAT, COTONOU_LNG, PORTO_NOVO_LAT, PORTO_NOVO_LNG,
                20_000, 1, null, null, null, PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(Trip::getId).containsExactly(matching.getId());
    }
}
