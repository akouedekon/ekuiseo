package bj.ekuiseo.api.service.kkiapay;

import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture d un webhook Kkiapay (constat F021 : la logique ne vit plus dans le DTO) : le corps
 * brut est desserialise ici ({@link #parse}), car le controleur le recoit tel quel pour qu il
 * soit hache et persiste avant traitement (contrat A.5) ; le champ {@code stateData} porte la
 * correlation. Kkiapay ne connait pas nos identifiants internes : {@code stateData} renvoie ce
 * que le frontend a fourni au parametre {@code data} du widget (voir
 * {@code frontend/src/lib/kkiapay.ts}). La documentation type ce parametre comme une chaine :
 * selon la version du widget, il revient donc soit comme un objet JSON, soit comme une chaine
 * contenant du JSON (eventuellement encodee deux fois). Les trois formes sont acceptees, avec
 * l {@link ObjectMapper} de Spring.
 */
@Component
public class KkiapayWebhookParser {

    private static final Logger log = LoggerFactory.getLogger(KkiapayWebhookParser.class);
    /** Au-dela, le corps n est conserve que tronque dans la trace du webhook. */
    private static final int MAX_STORED_RAW = 4_000;

    private final ObjectMapper objectMapper;

    public KkiapayWebhookParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Corps brut -> payload type ; null si le corps n est pas du JSON exploitable. */
    public KkiapayWebhookPayload parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, KkiapayWebhookPayload.class);
        } catch (Exception ex) {
            log.warn("Webhook illisible : {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Corps brut -> Map pour la trace jsonb ; un corps illisible est conserve tronque sous « raw ». */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(rawBody, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
            // corps non JSON : conserve brut ci-dessous
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("raw", rawBody.length() > MAX_STORED_RAW ? rawBody.substring(0, MAX_STORED_RAW) : rawBody);
        return fallback;
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
