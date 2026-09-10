package bj.ekuiseo.api.web;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.domain.enums.VehicleType;
import bj.ekuiseo.api.dto.trip.NearbyTripResponse;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.ReviewService;
import bj.ekuiseo.api.service.TripService;
import bj.ekuiseo.api.web.controller.TripController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * « Autour de moi » (GET /api/v1/trips/nearby) avec la vraie chaine de securite : public
 * (200 sans jeton, appelant null), identifiant du jeton transmis quand il y en a un, bornes
 * en 400 RFC 7807 avant tout appel au service, quota search: par IP.
 */
@WebMvcTest(controllers = TripController.class)
class TripControllerNearbyWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private TripService tripService;
    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private ReviewService reviewService;

    private static NearbyTripResponse sample(UUID tripId) {
        TripResponse trip = new TripResponse(tripId, null, null, TripType.QUOTIDIEN,
                "Abomey-Calavi", 6.4489, 2.3556, "Cotonou", 6.3703, 2.3912,
                Instant.parse("2026-09-11T06:30:00Z"), 3, 2, 500, true, null, null, TripStatus.PUBLISHED,
                null, Instant.parse("2026-09-10T08:00:00Z"), null, null, null, null, null);
        return new NearbyTripResponse(trip, 0.8, "Carrefour Agla", 6.3801, 2.3712);
    }

    @Test
    void anonymous_is200_withoutToken_andRequesterIsNull() throws Exception {
        UUID tripId = UUID.randomUUID();
        when(tripService.nearby(isNull(), eq(6.37), eq(2.39), isNull(), isNull(), eq(30)))
                .thenReturn(List.of(sample(tripId)));

        mockMvc.perform(fromNewIp(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].trip.id").value(tripId.toString()))
                .andExpect(jsonPath("$[0].distanceKm").value(0.8))
                .andExpect(jsonPath("$[0].boardingLabel").value("Carrefour Agla"))
                .andExpect(jsonPath("$[0].boardingLat").value(6.3801));
    }

    @Test
    void authenticated_passesTheTokenSubject_andFilters() throws Exception {
        User user = activeUser();
        when(tripService.nearby(any(), anyDouble(), anyDouble(), any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(authed(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39")
                        .param("radiusKm", "5").param("vehicleType", "TRICYCLE").param("limit", "10"), bearerFor(user)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(tripService).nearby(user.getId(), 6.37, 2.39, 5.0, VehicleType.TRICYCLE, 10);
    }

    @Test
    void outOfRange_is400_validation_beforeTheService() throws Exception {
        mockMvc.perform(fromNewIp(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39").param("radiusKm", "31")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        mockMvc.perform(fromNewIp(get("/api/v1/trips/nearby").param("lat", "95").param("lng", "2.39")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(fromNewIp(get("/api/v1/trips/nearby").param("lng", "2.39")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(tripService, never()).nearby(any(), anyDouble(), anyDouble(), any(), any(), anyInt());
    }

    @Test
    void unknownVehicleType_is400() throws Exception {
        mockMvc.perform(fromNewIp(get("/api/v1/trips/nearby").param("lat", "6.37").param("lng", "2.39").param("vehicleType", "BUS")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(tripService, never()).nearby(any(), anyDouble(), anyDouble(), any(), any(), anyInt());
    }
}
