package bj.ekuiseo.api.service.push;

import bj.ekuiseo.api.service.NotificationTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FCM v1 (V24) : message, en-tete d autorisation, issues SENT / GONE / FAILED / DISABLED. */
class FcmSenderTest {

    private final NotificationTemplates.Push content =
            new NotificationTemplates.Push("Trajet confirme", "Depart demain 07:30", "/bookings", "booking-1");

    @Test
    void disabled_withoutServiceAccount_orUnreadableOne() {
        assertThat(new FcmSender(RestClient.create(), "").isEnabled()).isFalse();
        assertThat(new FcmSender(RestClient.create(), "pas du json").isEnabled()).isFalse();
        assertThat(new FcmSender(RestClient.create(), "").send("token", content)).isEqualTo(WebPushSender.Outcome.DISABLED);
    }

    @Test
    void decode_acceptsRawJsonAndBase64() {
        String json = "{\"project_id\":\"ekuiseo\"}";
        assertThat(FcmSender.decode(json)).isEqualTo(json);
        assertThat(FcmSender.decode(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))).isEqualTo(json);
        assertThat(FcmSender.decode("  ")).isEmpty();
    }

    @Test
    void toMessageJson_carriesNotificationAndData() throws Exception {
        FcmSender sender = new FcmSender(RestClient.create(), "ekuiseo", () -> "jeton");
        JsonNode message = new ObjectMapper().readTree(sender.toMessageJson("tok-1", content)).path("message");
        assertThat(message.path("token").asText()).isEqualTo("tok-1");
        assertThat(message.path("notification").path("title").asText()).isEqualTo("Trajet confirme");
        assertThat(message.path("data").path("url").asText()).isEqualTo("/bookings");
        assertThat(message.path("android").path("notification").path("tag").asText()).isEqualTo("booking-1");
    }

    @Test
    void send_postsToTheProjectWithBearer_andMapsOutcomes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FcmSender sender = new FcmSender(builder.build(), "ekuiseo-prod", () -> "jeton-oauth");
        String url = "https://fcm.googleapis.com/v1/projects/ekuiseo-prod/messages:send";

        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer jeton-oauth"))
                .andExpect(jsonPath("$.message.token").value("tok-ok"))
                .andRespond(withSuccess("{\"name\":\"projects/ekuiseo-prod/messages/1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));
        server.expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"panne\"}}"));

        assertThat(sender.send("tok-ok", content)).isEqualTo(WebPushSender.Outcome.SENT);
        assertThat(sender.send("tok-gone", content)).isEqualTo(WebPushSender.Outcome.GONE);
        assertThat(sender.send("tok-fail", content)).isEqualTo(WebPushSender.Outcome.FAILED);
        server.verify();
    }

    @Test
    void send_withoutAccessToken_isFailedNotThrown() {
        FcmSender sender = new FcmSender(RestClient.create(), "ekuiseo", () -> null);
        assertThat(sender.send("tok", content)).isEqualTo(WebPushSender.Outcome.FAILED);
    }
}
