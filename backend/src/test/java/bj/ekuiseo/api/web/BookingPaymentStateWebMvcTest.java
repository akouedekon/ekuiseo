package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.CashStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.booking.BookingPaymentStateResponse;
import bj.ekuiseo.api.dto.booking.CashSettlementResponse;
import bj.ekuiseo.api.service.BookingPaymentStateService;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.CashSettlementService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.web.controller.BookingController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat A.2 ({@code GET /bookings/{id}/payment-state}) et A.6 ({@code /bookings/{id}/cash/*}) sur la
 * tranche MVC : le passager voit l etat consolide, un tiers recoit 403, l identifiant vient toujours
 * du jeton, aucun montant n est accepte du client.
 */
@WebMvcTest(controllers = BookingController.class)
class BookingPaymentStateWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private MessageService messageService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private BookingPaymentStateService bookingPaymentStateService;
    @MockitoBean
    private CashSettlementService cashSettlementService;

    @Test
    void passenger_getsTheConsolidatedState() throws Exception {
        User passenger = activeUser();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(bookingPaymentStateService.get(bookingId, passenger.getId())).thenReturn(new BookingPaymentStateResponse(
                bookingId, BookingStatus.CONFIRMED, PaymentMethod.MOMO_DEPOSIT, "SUCCEEDED", paymentId, "kk_123",
                1000, 1000L, Instant.parse("2026-09-10T10:00:00Z"), null, null,
                new CashSettlementResponse(CashStatus.EXPECTED, 6500, null, null, null),
                new BookingPaymentStateResponse.Ledger(1000, 600, 400, 0, 0, 6500)));

        mockMvc.perform(authed(get("/api/v1/bookings/" + bookingId + "/payment-state"), bearerFor(passenger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.paymentState").value("SUCCEEDED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.providerTxId").value("kk_123"))
                .andExpect(jsonPath("$.amountDueFcfa").value(1000))
                .andExpect(jsonPath("$.cash.status").value("EXPECTED"))
                .andExpect(jsonPath("$.cash.expectedFcfa").value(6500))
                .andExpect(jsonPath("$.ledger.platformCommissionFcfa").value(600))
                .andExpect(jsonPath("$.ledger.driverShareFcfa").value(400));
        verify(bookingPaymentStateService).get(bookingId, passenger.getId());
    }

    @Test
    void thirdParty_is403_andAnonymousIs401() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingPaymentStateService.get(any(), any())).thenThrow(new ForbiddenException("Vous n'etes pas autorise a consulter cette reservation"));

        mockMvc.perform(authed(get("/api/v1/bookings/" + bookingId + "/payment-state"), bearerFor(activeUser())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
        mockMvc.perform(fromNewIp(get("/api/v1/bookings/" + bookingId + "/payment-state")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cashRoutes_useTheTokenSubject_andRequireDisputeDetails() throws Exception {
        User user = activeUser();
        String bearer = bearerFor(user);
        UUID bookingId = UUID.randomUUID();
        CashSettlementResponse settled = new CashSettlementResponse(CashStatus.SETTLED, 6500, Instant.now(), Instant.now(), null);
        when(cashSettlementService.driverConfirm(bookingId, user.getId())).thenReturn(settled);
        when(cashSettlementService.passengerConfirm(bookingId, user.getId())).thenReturn(settled);
        when(cashSettlementService.dispute(bookingId, user.getId(), "Montant reclame different"))
                .thenReturn(new CashSettlementResponse(CashStatus.DISPUTED, 6500, null, null, Instant.now()));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cash/driver-confirm"), bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cash/passenger-confirm"), bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedFcfa").value(6500));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cash/dispute"), bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"details\":\"Montant reclame different\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"));
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cash/dispute"), bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"details\":\"  \"}"))
                .andExpect(status().isBadRequest());
        // Un montant envoye par le client est ignore : seul l identifiant compte.
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cash/driver-confirm"), bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountFcfa\":1}"))
                .andExpect(status().isOk());
        verify(cashSettlementService, never()).dispute(any(), any(), org.mockito.ArgumentMatchers.eq("  "));
        mockMvc.perform(fromNewIp(post("/api/v1/bookings/" + bookingId + "/cash/driver-confirm")))
                .andExpect(status().isUnauthorized());
    }
}
