package bj.ekuiseo.api.dto.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stateData revient de Kkiapay soit comme objet JSON, soit comme chaine JSON (le
 * parametre "data" du widget est type String) : la correlation bookingId doit
 * fonctionner dans les deux cas, et ne jamais lever sur une valeur inattendue.
 */
class KkiapayWebhookPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID BOOKING = UUID.fromString("03e72851-540c-4e81-9c3e-59acdf60854c");

    private KkiapayWebhookPayload parse(String json) throws Exception {
        return MAPPER.readValue(json, KkiapayWebhookPayload.class);
    }

    @Test
    void stateData_asObject() throws Exception {
        var p = parse("{\"transactionId\":\"abc\",\"isPaymentSucces\":true,"
                + "\"stateData\":{\"bookingId\":\"" + BOOKING + "\"}}");
        assertThat(p.paymentSucceeded()).isTrue();
        assertThat(p.extractBookingId()).isEqualTo(BOOKING);
        assertThat(p.extractSubscriptionId()).isNull();
    }

    @Test
    void stateData_asJsonString() throws Exception {
        String inner = MAPPER.writeValueAsString(Map.of("bookingId", BOOKING.toString()));
        var p = parse("{\"transactionId\":\"abc\",\"stateData\":" + MAPPER.writeValueAsString(inner) + "}");
        assertThat(p.extractBookingId()).isEqualTo(BOOKING);
    }

    @Test
    void stateData_asDoublyEncodedString() throws Exception {
        String inner = MAPPER.writeValueAsString(Map.of("subscriptionId", BOOKING.toString()));
        String outer = MAPPER.writeValueAsString(inner);
        var p = parse("{\"transactionId\":\"abc\",\"stateData\":" + MAPPER.writeValueAsString(outer) + "}");
        assertThat(p.extractSubscriptionId()).isEqualTo(BOOKING);
    }

    @Test
    void stateData_missingEmptyOrGarbage_yieldsNull() throws Exception {
        assertThat(parse("{\"transactionId\":\"abc\"}").extractBookingId()).isNull();
        assertThat(parse("{\"transactionId\":\"abc\",\"stateData\":{}}").extractBookingId()).isNull();
        assertThat(parse("{\"transactionId\":\"abc\",\"stateData\":\"\"}").extractBookingId()).isNull();
        assertThat(parse("{\"transactionId\":\"abc\",\"stateData\":\"pas du json\"}").extractBookingId()).isNull();
        assertThat(parse("{\"transactionId\":\"abc\",\"stateData\":42}").extractBookingId()).isNull();
        assertThat(parse("{\"transactionId\":\"abc\",\"stateData\":{\"bookingId\":\"nope\"}}").extractBookingId()).isNull();
    }
}
