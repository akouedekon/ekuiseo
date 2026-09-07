package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.payment.InitiatePaymentRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.service.kkiapay.KkiapayUnavailableException;
import bj.ekuiseo.api.web.controller.PaymentController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/payments/kkiapay/**} (constat F434) : l'initiation est authentifiee et
 * validee, le webhook est public mais n'atteint le service qu'avec la bonne signature
 * ({@code X-Kkiapay-Secret}), et le payload Kkiapay est desserialise tel que documente
 * ({@code isPaymentSucces}, {@code stateData} objet ou chaine).
 */
@WebMvcTest(controllers = PaymentController.class)
class PaymentControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String WEBHOOK = "/api/v1/payments/kkiapay/webhook";
    private static final String INITIATE = "/api/v1/payments/kkiapay/initiate";

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void initiate_anonymous_is401() throws Exception {
        mockMvc.perform(fromNewIp(json(post(INITIATE), new InitiatePaymentRequest(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(paymentService, never()).initiate(any(), any());
    }

    @Test
    void initiate_validBody_passesTokenSubjectAndBookingId() throws Exception {
        User passenger = activeUser();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentService.initiate(passenger.getId(), new InitiatePaymentRequest(bookingId))).thenReturn(
                new InitiatePaymentResponse(paymentId, "EKU-REF-2", 1000, "pk_test", true, Map.of("bookingId", bookingId.toString())));

        mockMvc.perform(authed(json(post(INITIATE), new InitiatePaymentRequest(bookingId)), bearerFor(passenger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.kkiapayPublicKey").value("pk_test"));
        verify(paymentService).initiate(passenger.getId(), new InitiatePaymentRequest(bookingId));
    }

    @Test
    void initiate_missingBookingId_is400ValidationProblem() throws Exception {
        mockMvc.perform(authed(json(post(INITIATE), Map.of()), bearerFor(activeUser())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bookingId")));
        verify(paymentService, never()).initiate(any(), any());
    }

    @Test
    void initiate_unknownBooking_is404_andAlreadyPaidIs409() throws Exception {
        User passenger = activeUser();
        String bearer = bearerFor(passenger);
        when(paymentService.initiate(eq(passenger.getId()), any()))
                .thenThrow(new NotFoundException("Reservation introuvable"))
                .thenThrow(new ConflictException("Reservation deja payee"));

        mockMvc.perform(authed(json(post(INITIATE), new InitiatePaymentRequest(UUID.randomUUID())), bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/not-found"));
        mockMvc.perform(authed(json(post(INITIATE), new InitiatePaymentRequest(UUID.randomUUID())), bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"))
                .andExpect(jsonPath("$.detail").value("Reservation deja payee"));
    }

    @Test
    void initiate_whenKkiapayIsDown_is503WithoutLeakingCause() throws Exception {
        User passenger = activeUser();
        when(paymentService.initiate(eq(passenger.getId()), any()))
                .thenThrow(new KkiapayUnavailableException("timeout vers api.kkiapay.me", new RuntimeException("socket")));

        mockMvc.perform(authed(json(post(INITIATE), new InitiatePaymentRequest(UUID.randomUUID())), bearerFor(passenger)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/upstream-unavailable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("kkiapay.me"))));
    }

    @Test
    void webhook_withoutSecretHeader_is400_andNeverReachesHandleWebhook() throws Exception {
        when(paymentService.verifySignature(null)).thenReturn(false);

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/bad-request"))
                .andExpect(jsonPath("$.detail").value("Signature de webhook invalide"));
        verify(paymentService).verifySignature(null);
        verify(paymentService, never()).handleWebhook(any());
    }

    @Test
    void webhook_withWrongSecret_is400() throws Exception {
        when(paymentService.verifySignature("mauvais")).thenReturn(false);

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID())).header("X-Kkiapay-Secret", "mauvais")))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).handleWebhook(any());
    }

    /** Public (pas de JWT) mais signe : le payload est transmis desserialise, stateData en objet. */
    @Test
    void webhook_withValidSecret_isPublic_andPassesDeserializedPayload() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(paymentService.verifySignature("secret-hash")).thenReturn(true);

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(bookingId)).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isOk());

        ArgumentCaptor<KkiapayWebhookPayload> captor = ArgumentCaptor.forClass(KkiapayWebhookPayload.class);
        verify(paymentService).handleWebhook(captor.capture());
        KkiapayWebhookPayload payload = captor.getValue();
        assertThat(payload.transactionId()).isEqualTo("3iH6wjHJ3");
        assertThat(payload.paymentSucceeded()).isTrue();
        assertThat(payload.amount()).isEqualTo(1000L);
        assertThat(payload.event()).isEqualTo("transaction.success");
        assertThat(payload.extractBookingId()).isEqualTo(bookingId);
    }

    /** Certaines versions du widget renvoient stateData comme chaine JSON : bookingId doit etre retrouve. */
    @Test
    void webhook_withStateDataAsJsonString_stillExtractsBookingId() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(paymentService.verifySignature("secret-hash")).thenReturn(true);
        Map<String, Object> body = new java.util.HashMap<>(webhookBody(bookingId));
        body.put("stateData", "{\"bookingId\":\"" + bookingId + "\"}");
        body.put("unknownField", "ignore-moi");

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), body).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isOk());

        ArgumentCaptor<KkiapayWebhookPayload> captor = ArgumentCaptor.forClass(KkiapayWebhookPayload.class);
        verify(paymentService).handleWebhook(captor.capture());
        assertThat(captor.getValue().extractBookingId()).isEqualTo(bookingId);
    }

    /** Un webhook rejoue pour une reservation deja traitee ne doit pas sortir en 500 : le service leve un conflit propre. */
    @Test
    void webhook_whenServiceRaisesConflict_is409ProblemJson() throws Exception {
        when(paymentService.verifySignature("secret-hash")).thenReturn(true);
        org.mockito.Mockito.doThrow(new ConflictException("Transaction deja traitee")).when(paymentService).handleWebhook(any());

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID())).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static Map<String, Object> webhookBody(UUID bookingId) {
        return Map.ofEntries(
                Map.entry("event", "transaction.success"),
                Map.entry("transactionId", "3iH6wjHJ3"),
                Map.entry("isPaymentSucces", true),
                Map.entry("account", "22901970000"),
                Map.entry("label", "Ekuiseo"),
                Map.entry("method", "MOBILE_MONEY"),
                Map.entry("amount", 1000),
                Map.entry("fees", 19),
                Map.entry("partnerId", "partner"),
                Map.entry("performedAt", "2026-09-07T08:55:22.883Z"),
                Map.entry("stateData", Map.of("bookingId", bookingId.toString())));
    }
}
