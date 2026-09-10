package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.GlobalExceptionHandler;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.alert.TripAlertRequest;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.dto.report.CreateReportRequest;
import bj.ekuiseo.api.dto.trip.CreateTripRequest;
import bj.ekuiseo.api.dto.user.VehicleRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.ReviewService;
import bj.ekuiseo.api.service.TripService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bornes des entrees (constats F025/F026/F531/F547/F603) : un parametre de recherche
 * hors plage ou un texte trop long donne un 400 explicite avant tout acces au service,
 * et les DTO refusent les charges au-dela des colonnes qui les recoivent.
 */
class RequestBoundsTest {

    private final TripService tripService = mock(TripService.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TripController controller = new TripController(tripService, mock(BookingService.class),
                mock(ReviewService.class), mock(CurrentUser.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "{0}={1} -> 400")
    @CsvSource({
            "originLat, 999",
            "originLat, -91",
            "originLng, 181",
            "destLat, 90.5",
            "destLng, -180.1",
            "size, 0",
            "size, 51",
            "radiusKm, 0.5",
            "radiusKm, 51",
            "seats, 9",
            "page, -1",
    })
    void search_rejectsOutOfRangeParameters_withoutCallingTheService(String param, String value) throws Exception {
        // Les coordonnees valides par defaut, le parametre teste les remplacant (une seule valeur par nom).
        Map<String, String> params = new LinkedHashMap<>(Map.of(
                "originLat", "6.37", "originLng", "2.39", "destLat", "7.18", "destLng", "2.07"));
        params.put(param, value);
        MockHttpServletRequestBuilder request = get("/api/v1/trips/search");
        params.forEach(request::param);
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(tripService, never()).search(any(), any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), anyInt(), any(), any(), any());
    }

    @Test
    void search_rejectsATooLongLabel() throws Exception {
        mvc.perform(get("/api/v1/trips/search")
                        .param("originLat", "6.37").param("originLng", "2.39")
                        .param("destLat", "7.18").param("destLng", "2.07")
                        .param("originLabel", "x".repeat(256)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withinBounds_reachesTheService() throws Exception {
        when(tripService.search(any(), any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), anyInt(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        mvc.perform(get("/api/v1/trips/search")
                        .param("originLat", "6.37").param("originLng", "2.39")
                        .param("destLat", "7.18").param("destLng", "2.07")
                        .param("radiusKm", "50").param("size", "50"))
                .andExpect(status().isOk());
    }

    /** « Autour de moi » (GET /trips/nearby) : memes garde-fous que la recherche, rayon 1 a 30 km, 1 a 50 resultats. */
    @ParameterizedTest(name = "nearby {0}={1} -> 400")
    @CsvSource({
            "lat, 91",
            "lat, -90.5",
            "lng, 180.1",
            "lng, -181",
            "radiusKm, 0.9",
            "radiusKm, 31",
            "limit, 0",
            "limit, 51",
            "vehicleType, BUS",
    })
    void nearby_rejectsOutOfRangeParameters_withoutCallingTheService(String param, String value) throws Exception {
        Map<String, String> params = new LinkedHashMap<>(Map.of("lat", "6.37", "lng", "2.39"));
        params.put(param, value);
        MockHttpServletRequestBuilder request = get("/api/v1/trips/nearby");
        params.forEach(request::param);
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(tripService, never()).nearby(any(), anyDouble(), anyDouble(), any(), any(), anyInt());
    }

    @Test
    void nearby_withinBounds_reachesTheService_withDefaults() throws Exception {
        when(tripService.nearby(any(), anyDouble(), anyDouble(), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        // Rayon absent (le service applique 10 km), limite par defaut 30, tous les vehicules.
        verify(tripService).nearby(null, 6.37, 2.39, null, null, 30);

        mvc.perform(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39")
                        .param("radiusKm", "30").param("vehicleType", "MOTO").param("limit", "50"))
                .andExpect(status().isOk());
        verify(tripService).nearby(null, 6.37, 2.39, 30.0, bj.ekuiseo.api.domain.enums.VehicleType.MOTO, 50);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedPayloads")
    void oversizedOrOutOfRangePayload_isRejected(String label, Object payload, String field) {
        Set<ConstraintViolation<Object>> violations = validator.validate(payload);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains(field);
    }

    static Stream<Arguments> oversizedPayloads() {
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        return Stream.of(
                Arguments.of("message de 2001 caracteres", new SendMessageRequest("m".repeat(2001)), "body"),
                Arguments.of("details de signalement de 501 caracteres",
                        new CreateReportRequest(UUID.randomUUID(), null, ReportReason.FRAUD, "d".repeat(501)), "details"),
                Arguments.of("plaque de 21 caracteres",
                        new VehicleRequest("Toyota", "Corolla", "gris", "P".repeat(21), 4, null, ComfortLevel.BASIC, null), "plate"),
                Arguments.of("description de trajet de 2001 caracteres",
                        new CreateTripRequest(UUID.randomUUID(), TripType.INTERURBAIN, "Cotonou", 6.37, 2.39,
                                "Bohicon", 7.18, 2.07, tomorrow, 3, 2500, true, null, "x".repeat(2001), null, List.of()),
                        "description"),
                Arguments.of("latitude 999 a la publication",
                        new CreateTripRequest(UUID.randomUUID(), TripType.INTERURBAIN, "Cotonou", 999.0, 2.39,
                                "Bohicon", 7.18, 2.07, tomorrow, 3, 2500, true, null, null, null, List.of()),
                        "originLat"),
                Arguments.of("alerte datee dans le passe",
                        new TripAlertRequest("Cotonou", 6.37, 2.39, "Bohicon", 7.18, 2.07,
                                LocalDate.now().minusDays(1), 1, TripType.INTERURBAIN, null), "date"),
                Arguments.of("alerte avec longitude 181",
                        new TripAlertRequest("Cotonou", 6.37, 181.0, "Bohicon", 7.18, 2.07,
                                null, 1, TripType.INTERURBAIN, null), "originLng")
        );
    }

    @Test
    void payloadsWithinBounds_areAccepted() {
        assertThat(validator.validate(new SendMessageRequest("m".repeat(2000)))).isEmpty();
        assertThat(validator.validate(new TripAlertRequest("Cotonou", 6.37, 2.39, "Bohicon", 7.18, 2.07,
                LocalDate.now(), 1, TripType.INTERURBAIN, null))).isEmpty();
    }
}
