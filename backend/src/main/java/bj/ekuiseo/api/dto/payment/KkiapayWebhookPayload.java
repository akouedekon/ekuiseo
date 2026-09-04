package bj.ekuiseo.api.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * internes. Le champ {@code stateData} renvoie ce que le frontend a fourni au parametre
 * {@code data} du widget (voir {@code frontend/src/lib/kkiapay.ts}). La documentation
 * type ce parametre comme une chaine : selon la version du widget, il revient donc soit
 * comme un objet JSON, soit comme une chaine contenant du JSON. Les deux formes sont
 * acceptees ici ({@code Object} + analyse tolerante), afin que {@code bookingId} /
 * {@code subscriptionId} soient retrouves dans tous les cas.</p>
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** Extrait le bookingId depuis stateData.bookingId (voir javadoc de la classe). Null si absent/invalide. */
    public UUID extractBookingId() {
        return extractUuid("bookingId");
    }

    /** Extrait le subscriptionId depuis stateData.subscriptionId (abonnement conducteur, regle metier n.11). */
    public UUID extractSubscriptionId() {
        return extractUuid("subscriptionId");
    }

    private UUID extractUuid(String key) {
        Map<String, Object> data = stateDataAsMap();
        if (data == null) {
            return null;
        }
        Object raw = data.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * {@code stateData} normalise en Map, que Kkiapay l'ait renvoye comme objet JSON ou
     * comme chaine JSON (eventuellement encodee deux fois). Null si absent ou inexploitable.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> stateDataAsMap() {
        Object current = stateData;
        // Au plus deux niveaux de "chaine contenant du JSON" : certains widgets serialisent
        // data une premiere fois, puis le webhook re-encode la chaine.
        for (int depth = 0; depth < 2; depth++) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            if (current instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) {
                    return null;
                }
                try {
                    current = MAPPER.readValue(trimmed, Object.class);
                } catch (Exception ex) {
                    return null;
                }
                continue;
            }
            return null;
        }
        return current instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
