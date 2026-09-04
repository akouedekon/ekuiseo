package bj.ekuiseo.api.service.kkiapay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Implementation reelle de {@link KkiapayGateway} via {@link RestClient}, contre
 * l'API HTTP de Kkiapay. Voir la javadoc de {@link KkiapayGateway} pour le detail
 * de ce qui est confirme et de ce qui reste a valider contre la documentation
 * officielle avant mise en production.
 */
public class KkiapayHttpGateway implements KkiapayGateway {

    private static final Logger log = LoggerFactory.getLogger(KkiapayHttpGateway.class);

    private final RestClient restClient;

    public KkiapayHttpGateway(String publicKey, String privateKey, String secretKey, boolean sandbox) {
        if (publicKey == null || publicKey.isBlank() || privateKey == null || privateKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "ekuiseo.kkiapay.mode=http requiert KKIAPAY_PUBLIC_KEY, KKIAPAY_PRIVATE_KEY et KKIAPAY_SECRET. "
                            + "Renseignez ces trois variables ou repassez en mode=stub pour le developpement.");
        }
        String baseUrl = sandbox ? "https://api-sandbox.kkiapay.me" : "https://api.kkiapay.me";
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", publicKey)
                .defaultHeader("x-private-key", privateKey)
                .defaultHeader("x-secret-key", secretKey)
                .build();
        log.info("KkiapayHttpGateway initialise (base={}, sandbox={})", baseUrl, sandbox);
    }

    @Override
    public VerificationResult verifyTransaction(String transactionId) {
        try {
            StatusApiResponse resp = restClient.post()
                    .uri("/api/v1/transactions/status")
                    .body(Map.of("transactionId", transactionId))
                    .retrieve()
                    .body(StatusApiResponse.class);
            if (resp == null) {
                return new VerificationResult(false, transactionId, 0, 0, "EMPTY_RESPONSE", "EMPTY_RESPONSE",
                        "Reponse vide de l'API Kkiapay");
            }
            boolean success = "SUCCESS".equalsIgnoreCase(resp.status()) || "SUCCESSFUL".equalsIgnoreCase(resp.status());
            return new VerificationResult(success, resp.transactionId() != null ? resp.transactionId() : transactionId,
                    resp.amount() != null ? resp.amount() : 0, resp.fees() != null ? resp.fees() : 0,
                    resp.status(), resp.failureCode(), resp.failureMessage());
        } catch (RestClientResponseException ex) {
            log.error("Kkiapay verifyTransaction a repondu {} pour transactionId={} : {}",
                    ex.getStatusCode(), transactionId, ex.getResponseBodyAsString());
            return new VerificationResult(false, transactionId, 0, 0, "HTTP_" + ex.getStatusCode().value(),
                    "HTTP_ERROR", "Reponse HTTP " + ex.getStatusCode() + " lors de la verification");
        } catch (RestClientException ex) {
            log.error("Echec reseau lors de la verification Kkiapay pour transactionId={}", transactionId, ex);
            throw new KkiapayUnavailableException("Impossible de contacter Kkiapay pour verifier la transaction", ex);
        }
    }

    @Override
    public RefundResult refundTransaction(String transactionId) {
        try {
            var resp = restClient.post()
                    .uri("/api/v1/transactions/revert")
                    .body(Map.of("transactionId", transactionId))
                    .retrieve()
                    .toBodilessEntity();
            boolean success = resp.getStatusCode().is2xxSuccessful();
            return new RefundResult(success, resp.getStatusCode().toString(),
                    success ? "Remboursement demande aupres de Kkiapay" : "Reponse non 2xx de Kkiapay");
        } catch (RestClientResponseException ex) {
            log.error("Kkiapay refundTransaction a repondu {} pour transactionId={} : {}",
                    ex.getStatusCode(), transactionId, ex.getResponseBodyAsString());
            return new RefundResult(false, "HTTP_" + ex.getStatusCode().value(),
                    "Reponse HTTP " + ex.getStatusCode() + " lors du remboursement");
        } catch (RestClientException ex) {
            log.error("Echec reseau lors du remboursement Kkiapay pour transactionId={}", transactionId, ex);
            throw new KkiapayUnavailableException("Impossible de contacter Kkiapay pour rembourser la transaction", ex);
        }
    }

    /**
     * Sous-ensemble mappe de la reponse de {@code POST /api/v1/transactions/status}. Le SDK
     * officiel documente une reponse plus riche (source, client{fullname,phone,email}, etc.) :
     * seuls les champs utiles ici sont mappes, le reste est ignore.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatusApiResponse(String transactionId, String status, Long amount, Long fees,
                                      String failureCode, String failureMessage) {
    }
}
