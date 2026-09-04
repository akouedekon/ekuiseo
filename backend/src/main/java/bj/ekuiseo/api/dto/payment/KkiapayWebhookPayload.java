package bj.ekuiseo.api.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
 * <p><b>Correlation avec la reservation</b> : Kkiapay ne connait pas nos identifiants
 * internes. Le champ {@code stateData} est un objet JSON libre que Kkiapay renvoie
 * tel quel s'il a ete fourni en parametre "data" a l'ouverture du widget cote frontend.
 * Ce backend n'initie PAS la transaction lui-meme (voir KkiapayGateway) : c'est donc
 * au frontend de passer {@code data: { bookingId: "<uuid de la reservation>" }} lors
 * de l'ouverture du widget, afin que {@code stateData.bookingId} permette de retrouver
 * la reservation ici. A confirmer avec l'equipe frontend/l'integration widget reelle.</p>
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
        Map<String, Object> stateData
) {

    /** Extrait le bookingId depuis stateData.bookingId (voir javadoc de la classe). Null si absent/invalide. */
    public UUID extractBookingId() {
        if (stateData == null) {
            return null;
        }
        Object raw = stateData.get("bookingId");
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Extrait le subscriptionId depuis stateData.subscriptionId (abonnement conducteur, regle metier n.11). */
    public UUID extractSubscriptionId() {
        if (stateData == null) {
            return null;
        }
        Object raw = stateData.get("subscriptionId");
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
