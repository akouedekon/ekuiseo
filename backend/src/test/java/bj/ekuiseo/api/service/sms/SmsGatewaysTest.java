package bj.ekuiseo.api.service.sms;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contrats HTTP des fournisseurs SMS reels (forme exacte des requetes, lecture des
 * reponses), rejoues contre un serveur simule : aucun SMS n'est envoye ici.
 */
class SmsGatewaysTest {

    private static final String PHONE = "+2290196870371";

    @Test
    void twilio_postsFormWithBasicAuth_andAcceptsCreated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACxxx/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("ACxxx:secret".getBytes())))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("To=%2B2290196870371")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("From=Ekuiseo")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sid\":\"SM123\",\"status\":\"queued\"}"));

        new TwilioSmsGateway(builder, "", "ACxxx", "secret", "Ekuiseo").send(PHONE, "Code 123456");
        server.verify();
    }

    @Test
    void twilio_rejection_isDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACxxx/Messages.json"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}"));

        assertThatThrownBy(() -> new TwilioSmsGateway(builder, "", "ACxxx", "secret", "Ekuiseo").send(PHONE, "x"))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void africasTalking_postsFormWithApiKey_andReadsRecipientStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.africastalking.com/version1/messaging"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apiKey", "atsk_key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("username=ekuiseo")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("to=%2B2290196870371")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("from=EKUISEO")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"SMSMessageData\":{\"Message\":\"Sent to 1/1 Total Cost: XOF 20\","
                                + "\"Recipients\":[{\"number\":\"+2290196870371\",\"status\":\"Success\","
                                + "\"statusCode\":101,\"messageId\":\"ATXid\",\"cost\":\"XOF 20\"}]}}"));

        new AfricasTalkingSmsGateway(builder, "", "ekuiseo", "atsk_key", "EKUISEO", false).send(PHONE, "Code 123456");
        server.verify();
    }

    @Test
    void africasTalking_perRecipientFailure_isDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.sandbox.africastalking.com/version1/messaging"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("username=sandbox")))
                .andRespond(withSuccess("{\"SMSMessageData\":{\"Message\":\"Sent to 0/1\",\"Recipients\":[{"
                        + "\"number\":\"+2290196870371\",\"status\":\"InsufficientBalance\",\"statusCode\":405}]}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new AfricasTalkingSmsGateway(builder, "", "ekuiseo", "atsk_key", null, true)
                .send(PHONE, "x"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("InsufficientBalance");
    }

    @Test
    void smsPartner_postsJson_andAcceptsSuccessTrue() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.smspartner.fr/v1/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"apiKey\":\"sp_key\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"phoneNumbers\":\"+2290196870371\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"sender\":\"Ekuiseo\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"sandbox\":1")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"message_id\":\"abc\",\"nb_sms\":1,"
                        + "\"cost\":\"0.155\",\"currency\":\"EUR\"}", MediaType.APPLICATION_JSON));

        new SmsPartnerSmsGateway(builder, "", "sp_key", "Ekuiseo", true).send(PHONE, "Code 123456");
        server.verify();
    }

    @Test
    void smsPartner_successFalse_isDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.smspartner.fr/v1/send"))
                .andRespond(withSuccess("{\"success\":false,\"code\":10,\"message\":\"Cle API incorrecte\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new SmsPartnerSmsGateway(builder, "", "sp_key", null, false).send(PHONE, "x"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("code 10");
        assertThatThrownBy(() -> new SmsPartnerSmsGateway(RestClient.builder(), "", "sp_key", "Ekuiseo Bénin", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void incompleteConfiguration_failsFast() {
        assertThatThrownBy(() -> new TwilioSmsGateway(RestClient.builder(), "", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AfricasTalkingSmsGateway(RestClient.builder(), "", "", "", "", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new LoggingSmsGateway()).isNotNull();
    }
}
