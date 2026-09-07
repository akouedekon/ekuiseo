package bj.ekuiseo.api.service.kkiapay;

import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Lecture du champ {@code stateData} d un webhook Kkiapay (constat F021 : la logique ne vit
 * plus dans le DTO). Kkiapay ne connait pas nos identifiants internes : {@code stateData}
 * renvoie ce que le frontend a fourni au parametre {@code data} du widget (voir
 * {@code frontend/src/lib/kkiapay.ts}). La documentation type ce parametre comme une
 * chaine : selon la version du widget, il revient donc soit comme un objet JSON, soit
 * comme une chaine contenant du JSON (eventuellement encodee deux fois). Les trois formes
 * sont acceptees, avec l {@link ObjectMapper} de Spring.
 */
@Component
public class KkiapayWebhookParser {

    private final ObjectMapper objectMapper;

    public KkiapayWebhookParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** bookingId porte par stateData, ou null s il est absent ou invalide. */
    public UUID extractBookingId(KkiapayWebhookPayload payload) {
        return extractUuid(payload, "bookingId");
    }

    /** subscriptionId porte par stateData (abonnement conducteur, regle metier n.11), ou null. */
    public UUID extractSubscriptionId(KkiapayWebhookPayload payload) {
        return extractUuid(payload, "subscriptionId");
    }

    private UUID extractUuid(KkiapayWebhookPayload payload, String key) {
        Map<String, Object> data = stateDataAsMap(payload == null ? null : payload.stateData());
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
    public Map<String, Object> stateDataAsMap(Object stateData) {
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
                    current = objectMapper.readValue(trimmed, Object.class);
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
