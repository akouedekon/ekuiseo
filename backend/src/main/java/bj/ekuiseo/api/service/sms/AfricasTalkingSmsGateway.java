package bj.ekuiseo.api.service.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Envoi par Africa's Talking (agregateur present au Benin, Togo, Nigeria...) :
 * POST formulaire sur {@code /version1/messaging} avec l'en-tete {@code apiKey},
 * champs {@code username}, {@code to}, {@code message} et {@code from} (identifiant
 * expediteur approuve, facultatif). La reponse est 201 mais chaque destinataire porte
 * son propre statut : seul {@code "Success"} vaut envoi ; les autres (numero invalide,
 * credit insuffisant, expediteur non approuve...) sont traites comme un echec.
 *
 * <p>Bac a sable : {@code AT_SANDBOX=true} envoie vers
 * {@code https://api.sandbox.africastalking.com} avec le nom d'utilisateur
 * {@code sandbox} ; les SMS s'affichent dans le simulateur de la console.</p>
 */
public class AfricasTalkingSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(AfricasTalkingSmsGateway.class);

    private final RestClient restClient;
    private final String username;
    private final String senderId;

    public AfricasTalkingSmsGateway(RestClient.Builder builder, String baseUrl, String username, String apiKey,
                                    String senderId, boolean sandbox) {
        if (isBlank(username) || isBlank(apiKey)) {
            throw new IllegalStateException("SMS_PROVIDER=africastalking requiert AT_USERNAME et AT_API_KEY "
                    + "(AT_SENDER_ID facultatif, AT_SANDBOX=true pour le bac a sable).");
        }
        this.username = sandbox ? "sandbox" : username;
        this.senderId = senderId;
        String url = !isBlank(baseUrl) ? baseUrl
                : sandbox ? "https://api.sandbox.africastalking.com" : "https://api.africastalking.com";
        this.restClient = builder
                .baseUrl(url)
                .defaultHeader("apiKey", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void send(String phoneE164, String message) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("to", phoneE164);
        form.add("message", message);
        if (!isBlank(senderId)) {
            form.add("from", senderId);
        }
        SendResponse response;
        try {
            response = restClient.post()
                    .uri("/version1/messaging")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SendResponse.class);
        } catch (RestClientResponseException ex) {
            log.error("Africa's Talking a refuse l'envoi a {} : {} {}", phoneE164, ex.getStatusCode(),
                    ex.getResponseBodyAsString());
            throw new SmsDeliveryException("Impossible d'envoyer le SMS (Africa's Talking "
                    + ex.getStatusCode().value() + ")", ex);
        } catch (RestClientException ex) {
            log.error("Echec reseau vers Africa's Talking pour {}", phoneE164, ex);
            throw new SmsDeliveryException("Impossible de joindre Africa's Talking", ex);
        }
        Recipient recipient = response == null || response.data() == null || response.data().recipients() == null
                || response.data().recipients().isEmpty() ? null : response.data().recipients().get(0);
        if (recipient == null || !"Success".equalsIgnoreCase(recipient.status())) {
            String status = recipient == null ? String.valueOf(response == null ? null : response.data()) : recipient.status();
            log.error("Africa's Talking n'a pas accepte le SMS pour {} : {}", phoneE164, status);
            throw new SmsDeliveryException("SMS refuse par Africa's Talking : " + status, null);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(@JsonProperty("SMSMessageData") MessageData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageData(@JsonProperty("Message") String message, @JsonProperty("Recipients") List<Recipient> recipients) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipient(@JsonProperty("number") String number, @JsonProperty("status") String status,
                     @JsonProperty("statusCode") Integer statusCode, @JsonProperty("messageId") String messageId) {
    }
}
