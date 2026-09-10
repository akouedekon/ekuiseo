package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser;
import bj.ekuiseo.api.service.payment.PaymentProviderUnavailableException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/payments/kkiapay/webhook} (constat F434, contrat A.5) : public, le corps brut et
 * l en-tete {@code X-Kkiapay-Secret} sont transmis tels quels a {@code PaymentService#receiveWebhook}
 * (qui persiste l evenement avant de verifier la signature et de traiter) ; le payload Kkiapay
 * se desserialise comme documente ({@code isPaymentSucces}, {@code stateData} objet ou chaine).
 * L'ancien alias {@code /initiate} n'existe plus (constat F015).
 */
@WebMvcTest(controllers = PaymentController.class)
class PaymentControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String WEBHOOK = "/api/v1/payments/kkiapay/webhook";
    private static final String LEGACY_INITIATE = "/api/v1/payments/kkiapay/initiate";
    private static final KkiapayWebhookParser PARSER = new KkiapayWebhookParser(new ObjectMapper().findAndRegisterModules());

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void legacyInitiateAlias_isGone() throws Exception {
        mockMvc.perform(authed(json(post(LEGACY_INITIATE), Map.of("bookingId", UUID.randomUUID().toString())), bearerFor(activeUser())))
                .andExpect(status().isNotFound());
        verify(paymentService, never()).initiate(any(), any());
    }

    /** Public (pas de JWT) : corps brut et en-tete (ou son absence) sont transmis au service, qui enregistre et decide. */
    @Test
    void webhook_isPublic_andPassesRawBodyAndHeaderToTheService() throws Exception {
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(bookingId)).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isOk());
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(paymentService).receiveWebhook(raw.capture(), eq("secret-hash"));
        KkiapayWebhookPayload payload = PARSER.parse(raw.getValue());
        assertThat(payload.transactionId()).isEqualTo("3iH6wjHJ3");
        assertThat(payload.paymentSucceeded()).isTrue();
        assertThat(payload.amount()).isEqualTo(1000L);
        assertThat(payload.event()).isEqualTo("transaction.success");
        assertThat(PARSER.extractBookingId(payload)).isEqualTo(bookingId);

        // Sans en-tete : 200 aussi (l evenement est enregistre REJECTED par le service, jamais 401 :
        // un rejeu infini cote Kkiapay n apporterait rien).
        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(bookingId))))
                .andExpect(status().isOk());
        verify(paymentService).receiveWebhook(any(), eq(null));
    }

    /** Certaines versions du widget renvoient stateData comme chaine JSON : bookingId doit etre retrouve. */
    @Test
    void webhook_withStateDataAsJsonString_stillExtractsBookingId() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Map<String, Object> body = new java.util.HashMap<>(webhookBody(bookingId));
        body.put("stateData", "{\"bookingId\":\"" + bookingId + "\"}");
        body.put("unknownField", "ignore-moi");

        mockMvc.perform(fromNewIp(json(post(WEBHOOK), body).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isOk());

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(paymentService).receiveWebhook(raw.capture(), eq("secret-hash"));
        assertThat(PARSER.extractBookingId(PARSER.parse(raw.getValue()))).isEqualTo(bookingId);
    }

    /** Un conflit metier ne sort pas en 500 ; une verification non conclusive sort en 503 pour que Kkiapay rejoue. */
    @Test
    void webhook_serviceErrors_areMappedToProblemJson() throws Exception {
        doThrow(new ConflictException("Transaction deja traitee")).when(paymentService).receiveWebhook(any(), any());
        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID())).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        doThrow(new PaymentProviderUnavailableException("non conclusif", null)).when(paymentService).receiveWebhook(any(), any());
        mockMvc.perform(fromNewIp(json(post(WEBHOOK), webhookBody(UUID.randomUUID())).header("X-Kkiapay-Secret", "secret-hash")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/upstream-unavailable"));
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
