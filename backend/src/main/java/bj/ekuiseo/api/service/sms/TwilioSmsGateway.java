package bj.ekuiseo.api.service.sms;

import bj.ekuiseo.api.common.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Envoi par Twilio (API Messages) : POST formulaire sur
 * {@code /2010-04-01/Accounts/{sid}/Messages.json}, authentification HTTP Basic
 * (Account SID / Auth Token), champs {@code To}, {@code From} (numero Twilio ou
 * identifiant alphanumerique) et {@code Body}. Twilio repond 201 avec le SID du
 * message ; toute reponse non 2xx est une erreur de livraison.
 *
 * <p>Note pour le Benin (+229) : un compte Twilio d'essai n'envoie qu'aux numeros
 * verifies dans la console ; un compte payant avec un expediteur alphanumerique
 * ("Ekuiseo") est necessaire pour les vrais utilisateurs.</p>
 */
public class TwilioSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsGateway.class);

    private final RestClient restClient;
    private final String accountSid;
    private final String from;

    public TwilioSmsGateway(RestClient.Builder builder, String baseUrl, String accountSid, String authToken, String from) {
        if (isBlank(accountSid) || isBlank(authToken) || isBlank(from)) {
            throw new IllegalStateException("SMS_PROVIDER=twilio requiert TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN et "
                    + "TWILIO_FROM (numero Twilio ou identifiant alphanumerique).");
        }
        this.accountSid = accountSid;
        this.from = from;
        this.restClient = builder
                .baseUrl(isBlank(baseUrl) ? "https://api.twilio.com" : baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(accountSid, authToken))
                .build();
    }

    @Override
    public void send(String phoneE164, String message) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneE164);
        form.add("From", from);
        form.add("Body", message);
        try {
            restClient.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            log.error("Twilio a refuse l'envoi a {} : {} {}", Masking.phone(phoneE164), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new SmsDeliveryException("Impossible d'envoyer le SMS (Twilio " + ex.getStatusCode().value() + ")", ex);
        } catch (RestClientException ex) {
            log.error("Echec reseau vers Twilio pour {}", Masking.phone(phoneE164), ex);
            throw new SmsDeliveryException("Impossible de joindre Twilio", ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
