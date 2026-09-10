package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.booking.ContestNoShowRequest;
import bj.ekuiseo.api.dto.message.MessageResponse;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.dto.payment.InitiateDepositRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.web.controller.BookingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/bookings/**} (constat F434) : tout est authentifie, l'identifiant du
 * passager vient du jeton (jamais du corps), les erreurs metier sortent en RFC 7807.
 */
@WebMvcTest(controllers = BookingController.class)
class BookingControllerWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private MessageService messageService;
    @MockitoBean
    private PaymentService paymentService;

    private User passenger;
    private String bearer;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        passenger = activeUser();
        bearer = bearerFor(passenger);
        bookingId = UUID.randomUUID();
    }

    @Test
    void anonymous_is401_onEveryRoute() throws Exception {
        mockMvc.perform(fromNewIp(get("/api/v1/bookings"))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(post("/api/v1/bookings/" + bookingId + "/cancel"))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(json(post("/api/v1/bookings/" + bookingId + "/payments/deposit"),
                        new InitiateDepositRequest(MobileMoneyOperator.MTN_MOMO, "+2290197000321"))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(bookingService, never()).cancelByPassenger(any(), any());
        verify(paymentService, never()).initiateDeposit(any(), any(), any());
    }

    @Test
    void cancel_callsServiceWithBookingAndTokenSubject() throws Exception {
        when(bookingService.cancelByPassenger(bookingId, passenger.getId()))
                .thenReturn(booking(bookingId, BookingStatus.CANCELLED_BY_PASSENGER));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED_BY_PASSENGER"));
        verify(bookingService).cancelByPassenger(bookingId, passenger.getId());
    }

    @Test
    void cancel_unknownBooking_is404() throws Exception {
        when(bookingService.cancelByPassenger(any(), any())).thenThrow(new NotFoundException("Reservation introuvable"));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings/" + bookingId + "/cancel"));
    }

    @Test
    void cancel_someoneElsesBooking_is403() throws Exception {
        when(bookingService.cancelByPassenger(any(), any())).thenThrow(new ForbiddenException("Cette reservation n est pas la votre"));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"))
                .andExpect(jsonPath("$.detail").value("Cette reservation n est pas la votre"));
    }

    @Test
    void cancel_afterDeparture_is409() throws Exception {
        when(bookingService.cancelByPassenger(any(), any())).thenThrow(new ConflictException("Le trajet est deja parti"));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void noShow_isReportedByTokenSubject() throws Exception {
        when(bookingService.markNoShow(bookingId, passenger.getId())).thenReturn(booking(bookingId, BookingStatus.NO_SHOW));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/no-show"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"));
        verify(bookingService).markNoShow(bookingId, passenger.getId());
    }

    /** V21 : constat du passager apres le depart, l identifiant du passager vient du jeton. */
    @Test
    void tripDone_andDriverNoShow_areRecordedForTheTokenSubject() throws Exception {
        when(bookingService.confirmTripDone(bookingId, passenger.getId())).thenReturn(booking(bookingId, BookingStatus.COMPLETED));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/trip-done"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(bookingService).confirmTripDone(bookingId, passenger.getId());

        when(bookingService.reportDriverNoShow(bookingId, passenger.getId(), "Personne au depart"))
                .thenReturn(booking(bookingId, BookingStatus.DRIVER_NO_SHOW));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/driver-no-show"), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"Personne au depart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRIVER_NO_SHOW"));
        verify(bookingService).reportDriverNoShow(bookingId, passenger.getId(), "Personne au depart");

        // Sans corps : accepte, details nuls. Details trop longs : 400 avant le service.
        when(bookingService.reportDriverNoShow(bookingId, passenger.getId(), null))
                .thenReturn(booking(bookingId, BookingStatus.DRIVER_NO_SHOW));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/driver-no-show"), bearer))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/driver-no-show"), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(fromNewIp(post("/api/v1/bookings/" + bookingId + "/trip-done")))
                .andExpect(status().isUnauthorized());
    }

    /** V25 : contestation du conducteur, explication obligatoire, identifiant du conducteur = sujet du jeton. */
    @Test
    void contestDriverNoShow_requiresDetails_andPassesTheTokenSubject() throws Exception {
        when(bookingService.contestDriverNoShow(eq(bookingId), eq(passenger.getId()), any()))
                .thenReturn(booking(bookingId, BookingStatus.DRIVER_NO_SHOW));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/contest-driver-no-show"), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"J etais a la gare a 6 h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRIVER_NO_SHOW"));
        ArgumentCaptor<ContestNoShowRequest> req = ArgumentCaptor.forClass(ContestNoShowRequest.class);
        verify(bookingService).contestDriverNoShow(eq(bookingId), eq(passenger.getId()), req.capture());
        assertThat(req.getValue().details()).isEqualTo("J etais a la gare a 6 h");

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/contest-driver-no-show"), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(fromNewIp(post("/api/v1/bookings/" + bookingId + "/contest-driver-no-show")))
                .andExpect(status().isUnauthorized());
    }

    /** V19 : accord et refus du conducteur, l identifiant du conducteur vient du jeton. */
    @Test
    void accept_confirmsTheRequest_forTheTokenSubject() throws Exception {
        when(bookingService.acceptByDriver(bookingId, passenger.getId())).thenReturn(booking(bookingId, BookingStatus.CONFIRMED));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/accept"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(bookingService).acceptByDriver(bookingId, passenger.getId());
    }

    @Test
    void decline_withReason_passesIt_andWithoutBodyPassesNull() throws Exception {
        when(bookingService.declineByDriver(eq(bookingId), eq(passenger.getId()), any()))
                .thenReturn(booking(bookingId, BookingStatus.CANCELLED_BY_DRIVER));

        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/decline"), Map.of("reason", "Vehicule plein")), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_BY_DRIVER"));
        verify(bookingService).declineByDriver(bookingId, passenger.getId(), "Vehicule plein");

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/decline"), bearer))
                .andExpect(status().isOk());
        verify(bookingService).declineByDriver(bookingId, passenger.getId(), null);
    }

    @Test
    void decline_withATooLongReason_is400_withoutCallingTheService() throws Exception {
        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/decline"), Map.of("reason", "x".repeat(301))), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        verify(bookingService, never()).declineByDriver(any(), any(), any());
    }

    @Test
    void accept_bySomeoneElse_is403() throws Exception {
        when(bookingService.acceptByDriver(any(), any())).thenThrow(new ForbiddenException("Vous n etes pas le conducteur de ce trajet"));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/accept"), bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
    }

    @Test
    void get_delegatesWithRequesterId() throws Exception {
        when(bookingService.getBookingDetailed(bookingId, passenger.getId()))
                .thenThrow(new NotFoundException("Reservation introuvable"));

        mockMvc.perform(authed(get("/api/v1/bookings/" + bookingId).param("expand", "trip,paymentPlan"), bearer))
                .andExpect(status().isNotFound());
        verify(bookingService).getBookingDetailed(bookingId, passenger.getId());
    }

    // Non couvert volontairement : un identifiant malforme dans le chemin (/bookings/pas-un-uuid)
    // ressort aujourd'hui en 500 (MethodArgumentTypeMismatchException n'est pas traduite par
    // GlobalExceptionHandler et tombe dans handleGeneric). A corriger cote main (400 bad-request),
    // puis a couvrir ici.

    @Test
    void initiateDeposit_validBody_passesBookingUserAndRequest() throws Exception {
        InitiateDepositRequest request = new InitiateDepositRequest(MobileMoneyOperator.MOOV_MONEY, "+2290197000321");
        UUID paymentId = UUID.randomUUID();
        when(paymentService.initiateDeposit(bookingId, passenger.getId(), request)).thenReturn(
                new InitiatePaymentResponse(paymentId, "EKU-REF-1", 1000, "pk_test", true, Map.of("bookingId", bookingId.toString())));

        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/payments/deposit"), request), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.sandbox").value(true))
                .andExpect(jsonPath("$.widgetData.bookingId").value(bookingId.toString()));
        verify(paymentService).initiateDeposit(bookingId, passenger.getId(), request);
    }

    @Test
    void initiateDeposit_missingProvider_is400ValidationProblem() throws Exception {
        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/payments/deposit"),
                        Map.of("phone", "+2290197000321")), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("provider")));
        verify(paymentService, never()).initiateDeposit(any(), any(), any());
    }

    @Test
    void initiateDeposit_blankPhone_is400() throws Exception {
        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/payments/deposit"),
                        Map.of("provider", "MTN_MOMO", "phone", "")), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("phone")));
        verify(paymentService, never()).initiateDeposit(any(), any(), any());
    }

    @Test
    void initiateDeposit_onCashBooking_is409() throws Exception {
        when(paymentService.initiateDeposit(eq(bookingId), eq(passenger.getId()), any()))
                .thenThrow(new ConflictException("Aucun acompte n est du pour une reservation en especes"));

        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/payments/deposit"),
                        new InitiateDepositRequest(MobileMoneyOperator.CELTIIS_CASH, "+2290197000321")), bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"));
    }

    @Test
    void sendMessage_is201_andBlankBodyIs400() throws Exception {
        SendMessageRequest request = new SendMessageRequest("Je suis devant la pharmacie");
        when(messageService.send(bookingId, passenger.getId(), request)).thenReturn(
                new MessageResponse(UUID.randomUUID(), UUID.randomUUID(), passenger.getId(), request.body(), null, Instant.now()));

        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/messages"), request), bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value(request.body()))
                .andExpect(jsonPath("$.senderId").value(passenger.getId().toString()));

        mockMvc.perform(authed(json(post("/api/v1/bookings/" + bookingId + "/messages"), Map.of("body", "   ")), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        verify(messageService).send(bookingId, passenger.getId(), request);
    }

    private static BookingResponse booking(UUID id, BookingStatus status) {
        return new BookingResponse(id, UUID.randomUUID(), UUID.randomUUID(), 1, 2500, 200, status,
                PaymentMethod.MOMO_DEPOSIT, Instant.now(), null, null, null, null, null, null);
    }
}
