package bj.ekuiseo.api.integration;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socle des tests d'integration (classes {@code *IT}, executees par maven-failsafe en
 * {@code mvn verify}) : un vrai PostGIS demarre par Testcontainers, les migrations Flyway
 * rejouees, {@code ddl-auto=validate} exerce pour de bon, et les requetes natives
 * executees contre le moteur reel (constats F121/F433 de l'audit).
 *
 * <p><b>Sans Docker, ces tests sont ignores</b> ({@code disabledWithoutDocker}) : ils
 * tournent en CI (ubuntu-latest) et sur tout poste ou Docker est demarre. Un seul
 * conteneur sert toute la JVM de test (demarre a la premiere demande, arrete par Ryuk a la
 * fin) ; chaque test repart d'une base vide ({@link #cleanDatabase()}), sauf le referentiel
 * {@code geo_places} seme par la migration V3.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("it")
public abstract class AbstractPostgisIT {

    // Coordonnees de reference (referentiel V3).
    protected static final double COTONOU_LAT = 6.3703;
    protected static final double COTONOU_LNG = 2.3912;
    protected static final double PORTO_NOVO_LAT = 6.4969;
    protected static final double PORTO_NOVO_LNG = 2.6289;
    protected static final double CALAVI_LAT = 6.4489;
    protected static final double CALAVI_LNG = 2.3556;
    protected static final double BOHICON_LAT = 7.1783;
    protected static final double BOHICON_LNG = 2.0667;
    protected static final double PARAKOU_LAT = 9.3372;
    protected static final double PARAKOU_LNG = 2.6303;
    protected static final double NATITINGOU_LAT = 10.3042;
    protected static final double NATITINGOU_LNG = 1.3796;

    /** Construit sans toucher Docker : le demarrage n'a lieu que si la classe n'est pas ignoree. */
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ekuiseo")
            .withUsername("ekuiseo")
            .withPassword("ekuiseo");

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    @DynamicPropertySource
    static synchronized void datasourceProperties(DynamicPropertyRegistry registry) {
        if (!POSTGIS.isRunning()) {
            POSTGIS.start();
        }
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected TransactionTemplate transactionTemplate;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected VehicleRepository vehicleRepository;
    @Autowired
    protected TripRepository tripRepository;

    /**
     * Base vide avant chaque test : tout ce qui depend de {@code users} (vehicules, trajets,
     * reservations, paiements, journaux...) est vide en cascade ; {@code search_events} n'a
     * pas de cle etrangere et est vide explicitement. {@code geo_places} est conserve.
     */
    @BeforeEach
    void cleanDatabase() {
        // Les executeurs asynchrones (notifications, remboursements) peuvent encore tenir un
        // verrou du test precedent : le TRUNCATE est retente quelques fois plutot que d echouer
        // sur un interblocage passager.
        org.springframework.dao.DataAccessException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                // payment_webhook_events n a pas de cle etrangere (V26) : vide explicitement.
                jdbcTemplate.execute("truncate table users, search_events, payment_webhook_events cascade");
                return;
            } catch (org.springframework.dao.DataAccessException ex) {
                last = ex;
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }

    protected User newUser(String firstName, Role role) {
        return newUser(firstName, role, UserStatus.ACTIVE);
    }

    /** Numero beninois au format E.164 a 10 chiffres (+229 01 ...), unique par appel. */
    protected User newUser(String firstName, Role role, UserStatus status) {
        return userRepository.save(User.builder()
                .phone(String.format("+22901970%05d", PHONE_SEQ.incrementAndGet()))
                .firstName(firstName)
                .lastName("Test")
                .email(firstName.toLowerCase() + PHONE_SEQ.get() + "@example.test")
                .passwordHash("x")
                .status(status)
                .role(role)
                .build());
    }

    protected Vehicle newVehicle(User owner) {
        return vehicleRepository.save(Vehicle.builder()
                .owner(owner)
                .brand("Toyota")
                .model("Corolla")
                .plate("AB-" + PHONE_SEQ.incrementAndGet() + "-BJ")
                .seats(4)
                .comfortLevel(ComfortLevel.COMFORT)
                .build());
    }

    /** Trajet PUBLISHED, toutes places disponibles. */
    protected Trip newTrip(User driver, Vehicle vehicle, TripType type,
                           String originLabel, double originLat, double originLng,
                           String destLabel, double destLat, double destLng,
                           Instant departureAt, int seats, long pricePerSeat) {
        return tripRepository.save(Trip.builder()
                .driver(driver).vehicle(vehicle)
                .tripType(type)
                .originLabel(originLabel).originLat(originLat).originLng(originLng)
                .destLabel(destLabel).destLat(destLat).destLng(destLng)
                .departureAt(departureAt)
                .seatsTotal(seats).seatsAvailable(seats)
                .pricePerSeat(pricePerSeat)
                .status(TripStatus.PUBLISHED)
                .build());
    }

    /** Instant correspondant a une date et heure civiles du Benin (UTC+1 sans heure d ete). */
    protected static Instant beninLocal(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(Tz.BENIN).toInstant();
    }
}
