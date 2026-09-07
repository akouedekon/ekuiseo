package bj.ekuiseo.api.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Payload du webhook Kkiapay, tel que documente publiquement (voir
 * {@link bj.ekuiseo.api.service.kkiapay.KkiapayGateway}) :
 * <pre>
 * {
 *   "transactionId": "3iH6wjHJ3",
 *   "isPaymentSucces": true,
 *   "account": "22996000000",
 *   "label": "...",
 *   "method": "MOBILE_MONEY",
 *   "amount": 1000,
 *   "fees": 19,
 *   "partnerId": "...",
 *   "performedAt": "2024-03-20T08:55:22.883Z",
 *   "stateData": {},
 *   "event": "transaction.success"
 * }
 * </pre>
 * Le champ {@code isPaymentSucces} est orthographie ainsi (sans le "s" final a
 * "Succes") dans l'API reelle Kkiapay ; conserve tel quel via {@code @JsonProperty}
 * pour eviter toute ambiguite de mappage.
 *
 * <p>{@code stateData} (objet JSON ou chaine contenant du JSON selon la version du widget)
 * porte la correlation {@code bookingId} / {@code subscriptionId} ; sa lecture est confiee a
 * {@link bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser} (constat F021 : un DTO ne
 * porte pas de logique).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KkiapayWebhookPayload(
        String event,
        String transactionId,
        @JsonProperty("isPaymentSucces") Boolean paymentSucceeded,
        String account,
        String label,
        String method,
        Long amount,
        Long fees,
        String partnerId,
        Instant performedAt,
        Object stateData
) {
}
