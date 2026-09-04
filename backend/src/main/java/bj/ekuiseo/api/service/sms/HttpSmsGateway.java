package bj.ekuiseo.api.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Implementation HTTP generique pour la production. AUCUN fournisseur SMS n'etant
 * impose au projet, cette classe appelle une URL configurable (ekuiseo.sms.http.url)
 * avec une cle API en en-tete (ekuiseo.sms.http.api-key), en POSTant un corps JSON
 * {@code {"to": "...", "message": "..."}}.
 *
 * <p><b>A adapter obligatoirement</b> au contrat exact du fournisseur SMS retenu
 * (URL, nom des champs, authentification) avant mise en production : ce corps de
 * requete et ces headers sont un choix raisonnable par defaut, pas la specification
 * d'un fournisseur reel verifie.</p>
 */
public class HttpSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpSmsGateway.class);

    private final RestClient restClient;
    private final String apiKey;

    public HttpSmsGateway(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ekuiseo.sms.mode=http requiert ekuiseo.sms.http.url (SMS_HTTP_URL) et "
                            + "ekuiseo.sms.http.api-key (SMS_PROVIDER_KEY). Renseignez ces variables ou "
                            + "repassez en mode=log pour le developpement.");
        }
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public void send(String phoneE164, String message) {
        try {
            restClient.post()
                    .uri("")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of("to", phoneE164, "message", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.error("Echec d'envoi SMS a {} via le fournisseur HTTP configure", phoneE164, ex);
            throw new SmsDeliveryException("Impossible d'envoyer le SMS", ex);
        }
    }
}
