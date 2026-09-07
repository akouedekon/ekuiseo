package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser;
import bj.ekuiseo.api.web.controller.PaymentController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/payments/kkiapay/webhook} (constat F434) : public mais n'atteint le service
 * qu'avec la bonne signature ({@code X-Kkiapay-Secret}, 401 sinon - constat F149), et le
 * payload Kkiapay est desserialise tel que documente ({@code isPaymentSucces}, {@code stateData}
 * objet ou chaine). L'ancien alias {@code /initiate} n'existe plus (constat F015) : l initiation
 * passe par {@code POST /api/v1/bookings/{id}/payments/deposit}.
 */
@WebMvcTest(controllers = PaymentController.class)
class PaymentControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String WEBHOOK = "/api/v1/payments/kkiapay/webhook";
    private static final String LEGACY_INITIATE = "/api/v1/payments/kkiapay/initiate";
    private static final KkiapayWebhookParser PARSER = new KkiapayWebhookParser(new ObjectMapper());

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void legacyInitiateAlias_isGone() throws Exception {
        mockMvc.perform(authed(json(post(LEGACY_INITIATE), Map.of("bookingId", UUID.randomUUID().toString())), bearerFor(activeUser())))
                .andExpect(status().isNotFound());
        verify(paymentService, never()).initiate(any(), any());
    }

    @Test
    void webhook_withoutSecretHeader_is401_andNeverReachesHandleWebhook() throws Exception {
        when(paymentService.verifySignature(null)).thenReturn(false);

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/unauthorized"))
                .andExpect(jsonPath("$.detail").value("Signature de webhook invalide"));
        verify(paymentService).verifySignature(null);
        verify(paymentService, never()).handleWebhook(any());
    }

    @Test
    void webhook_withWrongSecret_is401() throws Exception {
        when(paymentService.verifySignature("mauvais")).thenReturn(false);

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID())).header("X-Kkiapay-Secret", "mauvais")))
                .andExpect(status().isUnauthorized());
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
        assertThat(PARSER.extractBookingId(payload)).isEqualTo(bookingId);
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
        assertThat(PARSER.extractBookingId(captor.getValue())).isEqualTo(bookingId);
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
