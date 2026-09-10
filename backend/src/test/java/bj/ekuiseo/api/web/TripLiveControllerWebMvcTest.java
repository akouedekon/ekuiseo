package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LivePositionAck;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.service.TripLiveService;
import bj.ekuiseo.api.service.live.LocationUpdateService;
import bj.ekuiseo.api.web.controller.TripLiveController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suivi en direct (V23/V28) : les routes du trajet sont authentifiees et l identifiant de
 * l appelant vient du jeton ; le flux SSE repond en text/event-stream sans tampon ; les
 * positions rendent l accuse du contrat (acceptee, flags, cadence) et 429 sur cadence ; le
 * lien public par jeton repond sans session ; validation Bean et erreurs metier en RFC 7807.
 */
@WebMvcTest(controllers = TripLiveController.class)
class TripLiveControllerWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private TripLiveService tripLiveService;
    @MockitoBean
    private LocationUpdateService locationUpdateService;

    private User driver;
    private String bearer;
    private UUID tripId;

    @BeforeEach
    void setUp() {
        driver = activeUser();
        bearer = bearerFor(driver);
        tripId = UUID.randomUUID();
    }

    @Test
    void anonymous_is401_onTripRoutes_includingTheStream() throws Exception {
        mockMvc.perform(fromNewIp(json(put("/api/v1/trips/" + tripId + "/live"), Map.of("enabled", true))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(fromNewIp(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 6.4, "lng", 2.35))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(get("/api/v1/trips/" + tripId + "/live"))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(get("/api/v1/trips/" + tripId + "/live/stream").accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(tripLiveService, never()).setSharing(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(locationUpdateService, never()).record(any(), any(), any());
        verify(tripLiveService, never()).getLive(any(), any());
        verify(tripLiveService, never()).stream(any(), any());
    }

    @Test
    void enable_callsServiceWithTokenSubject_andReturnsTheInterval() throws Exception {
        when(tripLiveService.setSharing(tripId, driver.getId(), true))
                .thenReturn(new LiveSharingResponse(true, "abc", "/live/abc", null, 30));

        mockMvc.perform(authed(json(put("/api/v1/trips/" + tripId + "/live"), Map.of("enabled", true)), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.sharePath").value("/live/abc"))
                .andExpect(jsonPath("$.intervalSeconds").value(30));
        verify(tripLiveService).setSharing(tripId, driver.getId(), true);
    }

    @Test
    void enable_withoutFlag_is400_validation() throws Exception {
        mockMvc.perform(authed(json(put("/api/v1/trips/" + tripId + "/live"), Map.of()), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        verify(tripLiveService, never()).setSharing(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void enable_byAThirdParty_is403() throws Exception {
        when(tripLiveService.setSharing(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new ForbiddenException("Vous n etes pas le conducteur de ce trajet"));

        mockMvc.perform(authed(json(put("/api/v1/trips/" + tripId + "/live"), Map.of("enabled", true)), bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
    }

    @Test
    void postPosition_returnsTheAck_withFlagsAndInterval() throws Exception {
        when(locationUpdateService.record(eq(tripId), eq(driver.getId()), any()))
                .thenReturn(new LivePositionAck(true, List.of("LOW_ACCURACY"), 15));

        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"),
                        Map.of("lat", 6.4, "lng", 2.35, "heading", 310, "speedKmh", 62.5, "accuracyM", 600,
                                "recordedAt", "2026-09-10T08:00:00Z")), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.flags[0]").value("LOW_ACCURACY"))
                .andExpect(jsonPath("$.intervalSeconds").value(15));

        ArgumentCaptor<LivePositionRequest> req = ArgumentCaptor.forClass(LivePositionRequest.class);
        verify(locationUpdateService).record(eq(tripId), eq(driver.getId()), req.capture());
        assertThat(req.getValue().lat()).isEqualTo(6.4);
        assertThat(req.getValue().heading()).isEqualTo(310f);
        assertThat(req.getValue().accuracyM()).isEqualTo(600f);
        assertThat(req.getValue().recordedAt()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
    }

    @Test
    void postPosition_outOfRange_is400_validation() throws Exception {
        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 95, "lng", 2.35)), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 6.4, "lng", 2.35, "heading", 361)), bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lng", 2.35)), bearer))
                .andExpect(status().isBadRequest());
        verify(locationUpdateService, never()).record(any(), any(), any());
    }

    @Test
    void postPosition_byAnUnconfirmedPassenger_is403_andSharingOff_is400() throws Exception {
        when(locationUpdateService.record(any(), any(), any()))
                .thenThrow(new ForbiddenException("Seuls le conducteur et les passagers confirmes de ce trajet peuvent partager leur position"));
        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 6.4, "lng", 2.35)), bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));

        org.mockito.Mockito.reset(locationUpdateService);
        when(locationUpdateService.record(any(), any(), any()))
                .thenThrow(new BadRequestException("Le partage de position n est pas active sur ce trajet"));
        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 6.4, "lng", 2.35)), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le partage de position n est pas active sur ce trajet"));
    }

    @Test
    void postPosition_tooFrequent_is429_withRetryAfter() throws Exception {
        when(locationUpdateService.record(any(), any(), any()))
                .thenThrow(new TooManyRequestsException("Une position toutes les 2 secondes au plus", 1));

        mockMvc.perform(authed(json(post("/api/v1/trips/" + tripId + "/live/positions"), Map.of("lat", 6.4, "lng", 2.35)), bearer))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void getLive_forAViewer_returnsTheSnapshot_and403ForAStranger() throws Exception {
        Instant recorded = Instant.parse("2026-09-10T08:00:00Z");
        when(tripLiveService.getLive(tripId, driver.getId())).thenReturn(new LivePositionResponse(true,
                new LivePositionResponse.Position(6.4, 2.35, 310f, 60f, 10f, recorded),
                12L, TripStatus.ONGOING, Instant.parse("2026-09-10T07:30:00Z"), "abc", 15,
                List.of(new LiveParticipant(LiveRole.DRIVER, null, "Rodrigue", 6.4, 2.35, 310f, 60f, 10f, recorded, List.of())),
                recorded.plusSeconds(12)));

        mockMvc.perform(authed(get("/api/v1/trips/" + tripId + "/live"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.position.lat").value(6.4))
                .andExpect(jsonPath("$.staleSeconds").value(12))
                .andExpect(jsonPath("$.tripStatus").value("ONGOING"))
                .andExpect(jsonPath("$.shareToken").value("abc"))
                .andExpect(jsonPath("$.intervalSeconds").value(15))
                .andExpect(jsonPath("$.participants[0].role").value("DRIVER"))
                .andExpect(jsonPath("$.participants[0].firstName").value("Rodrigue"))
                .andExpect(jsonPath("$.serverTime").value("2026-09-10T08:00:12Z"));

        when(tripLiveService.getLive(any(), any())).thenThrow(new ForbiddenException("reserve"));
        mockMvc.perform(authed(get("/api/v1/trips/" + tripId + "/live"), bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void stream_isAnEventStream_withoutBuffering_startingWithTheSnapshot() throws Exception {
        when(tripLiveService.stream(tripId, driver.getId())).thenAnswer(inv -> {
            SseEmitter emitter = new SseEmitter(60_000L);
            emitter.send(SseEmitter.event().name("snapshot")
                    .data("{\"type\":\"snapshot\",\"tripStatus\":\"ONGOING\",\"sharingEnabled\":true,\"intervalSeconds\":15,\"participants\":[]}",
                            MediaType.APPLICATION_JSON));
            return emitter;
        });

        MvcResult result = mockMvc.perform(authed(get("/api/v1/trips/" + tripId + "/live/stream").accept(MediaType.TEXT_EVENT_STREAM), bearer))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .startsWith("event:snapshot\ndata:")
                .contains("\"type\":\"snapshot\"");
        verify(tripLiveService).stream(tripId, driver.getId());
    }

    @Test
    void stream_forAStranger_is403_problem() throws Exception {
        when(tripLiveService.stream(any(), any())).thenThrow(new ForbiddenException("reserve"));

        mockMvc.perform(authed(get("/api/v1/trips/" + tripId + "/live/stream").accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_PROBLEM_JSON), bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
    }

    @Test
    void publicLive_isReadableWithoutSession() throws Exception {
        when(tripLiveService.getPublic("abc")).thenReturn(new PublicLiveResponse(
                "Cotonou", 6.37, 2.39, "Bohicon", 7.18, 2.07, Instant.parse("2026-09-10T07:30:00Z"), TripStatus.ONGOING,
                "Rodrigue", new PublicLiveResponse.Vehicle("Toyota", "Corolla", "Grise"), null, null));

        mockMvc.perform(fromNewIp(get("/api/v1/live/abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverFirstName").value("Rodrigue"))
                .andExpect(jsonPath("$.vehicle.brand").value("Toyota"))
                .andExpect(jsonPath("$.position").doesNotExist());
    }

    @Test
    void publicLive_unknownToken_is404_problem() throws Exception {
        when(tripLiveService.getPublic("nope")).thenThrow(new NotFoundException("Suivi introuvable"));

        mockMvc.perform(fromNewIp(get("/api/v1/live/nope")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/not-found"));
    }
}
