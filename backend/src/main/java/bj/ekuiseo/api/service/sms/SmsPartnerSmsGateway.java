package bj.ekuiseo.api.service.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Envoi par SMS Partner (smspartner.fr / smspartner.africa) : plateforme en libre-service
 * (inscription gratuite, sans dossier d'entreprise, SMS offerts a l'ouverture, paiement
 * par carte ou PayPal) qui couvre MTN et Moov au Benin. Contrat : {@code POST
 * https://api.smspartner.fr/v1/send} avec un corps JSON {@code {"apiKey","phoneNumbers",
 * "message","sender","sandbox"}} ; la reponse porte {@code success} et {@code code}
 * (200 = accepte, sinon un code d'erreur : 10 = cle incorrecte, 429 = limite de debit).
 * Le numero est transmis au format international ({@code +229...}).
 *
 * <p>{@code SMSPARTNER_SANDBOX=true} envoie avec {@code sandbox: 1} : la plateforme
 * valide la requete sans debiter ni livrer (utile pour tester la configuration).</p>
 */
public class SmsPartnerSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SmsPartnerSmsGateway.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String sender;
    private final boolean sandbox;

    public SmsPartnerSmsGateway(RestClient.Builder builder, String baseUrl, String apiKey, String sender, boolean sandbox) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("SMS_PROVIDER=smspartner requiert SMSPARTNER_API_KEY "
                    + "(SMSPARTNER_SENDER facultatif, 3 a 11 caracteres alphanumeriques ; SMSPARTNER_SANDBOX=true pour tester).");
        }
        if (sender != null && !sender.isBlank() && !sender.matches("[A-Za-z0-9]{3,11}")) {
            throw new IllegalStateException("SMSPARTNER_SENDER doit faire 3 a 11 caracteres alphanumeriques, sans espace.");
        }
        this.apiKey = apiKey;
        this.sender = sender == null || sender.isBlank() ? null : sender;
        this.sandbox = sandbox;
        this.restClient = builder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.smspartner.fr" : baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void send(String phoneE164, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", apiKey);
        body.put("phoneNumbers", phoneE164);
        body.put("message", message);
        if (sender != null) {
            body.put("sender", sender);
        }
        if (sandbox) {
            body.put("sandbox", 1);
        }
        SendResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(SendResponse.class);
        } catch (RestClientResponseException ex) {
            log.error("SMS Partner a refuse l'envoi a {} : {} {}", phoneE164, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new SmsDeliveryException("Impossible d'envoyer le SMS (SMS Partner " + ex.getStatusCode().value() + ")", ex);
        } catch (RestClientException ex) {
            log.error("Echec reseau vers SMS Partner pour {}", phoneE164, ex);
            throw new SmsDeliveryException("Impossible de joindre SMS Partner", ex);
        }
        if (response == null || !Boolean.TRUE.equals(response.success())) {
            String detail = response == null ? "reponse vide"
                    : "code " + response.code() + (response.message() != null ? " : " + response.message() : "");
            log.error("SMS Partner n'a pas accepte le SMS pour {} ({})", phoneE164, detail);
            throw new SmsDeliveryException("SMS refuse par SMS Partner (" + detail + ")", null);
        }
        if (sandbox) {
            log.info("SMS Partner (bac a sable) : SMS accepte pour {} sans livraison, message_id={}", phoneE164,
                    response.messageId());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(Boolean success, Integer code, String message,
                        @JsonProperty("message_id") String messageId,
                        @JsonProperty("nb_sms") Integer nbSms, String cost, String currency) {
    }
}
