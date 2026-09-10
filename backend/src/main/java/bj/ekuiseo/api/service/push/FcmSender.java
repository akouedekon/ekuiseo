package bj.ekuiseo.api.service.push;

import bj.ekuiseo.api.service.NotificationTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Envoi de notifications a l application Android/iOS via Firebase Cloud Messaging (API HTTP v1,
 * V24). Le compte de service Firebase est lu dans {@code ekuiseo.push.fcm.service-account}
 * (contenu JSON, ou base64 du JSON) : vide, le canal est desactive proprement, comme Web Push.
 *
 * <p>Le message porte les memes champs que Web Push ({@code title}, {@code body}, {@code url},
 * {@code tag}) : la notification est affichee par le systeme, et {@code url} est reprise par
 * l application au toucher (frontend/src/lib/native.ts). Jamais de donnee personnelle au-dela
 * du texte de la notification.</p>
 */
@Service
public class FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final String projectId;
    private final Supplier<String> accessToken;

    public FcmSender(RestClient.Builder restClientBuilder,
                     @Value("${ekuiseo.push.fcm.service-account:}") String serviceAccount) {
        this(restClientBuilder.build(), serviceAccount);
    }

    FcmSender(RestClient restClient, String serviceAccount) {
        this.restClient = restClient;
        String raw = decode(serviceAccount);
        String project = null;
        Supplier<String> tokens = null;
        if (!raw.isEmpty()) {
            try {
                project = objectMapper.readTree(raw).path("project_id").asText(null);
                GoogleCredentials credentials = GoogleCredentials
                        .fromStream(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(List.of(SCOPE));
                tokens = () -> {
                    try {
                        credentials.refreshIfExpired();
                        AccessToken token = credentials.getAccessToken();
                        return token == null ? null : token.getTokenValue();
                    } catch (IOException ex) {
                        throw new IllegalStateException("Jeton FCM indisponible : " + ex.getMessage(), ex);
                    }
                };
                if (project == null || project.isBlank()) {
                    log.error("Notifications natives desactivees : le compte de service FCM ne porte pas de project_id");
                    tokens = null;
                } else {
                    log.info("Notifications natives (FCM) actives pour le projet {}", project);
                }
            } catch (IOException | IllegalArgumentException ex) {
                log.error("Notifications natives desactivees : compte de service FCM illisible ({})", ex.getMessage());
            }
        } else {
            log.info("Notifications natives (FCM) desactivees : FCM_SERVICE_ACCOUNT_JSON non renseigne");
        }
        this.projectId = tokens == null ? null : project;
        this.accessToken = tokens;
    }

    /** Constructeur de test : projet et fournisseur de jeton explicites. */
    FcmSender(RestClient restClient, String projectId, Supplier<String> accessToken) {
        this.restClient = restClient;
        this.projectId = projectId;
        this.accessToken = accessToken;
    }

    /** Le contenu peut etre le JSON brut ou son encodage base64 (plus commode dans un .env). */
    static String decode(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            return new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException ex) {
            return trimmed;
        }
    }

    public boolean isEnabled() {
        return accessToken != null && projectId != null;
    }

    /**
     * Envoie une notification a un jeton FCM. Ne leve jamais : tout echec est traduit en
     * {@link WebPushSender.Outcome} ; un jeton inconnu ou desinscrit (404, UNREGISTERED)
     * vaut {@code GONE} et l abonnement est supprime par l appelant.
     */
    public WebPushSender.Outcome send(String token, NotificationTemplates.Push content) {
        if (!isEnabled()) {
            return WebPushSender.Outcome.DISABLED;
        }
        try {
            String bearer = accessToken.get();
            if (bearer == null || bearer.isBlank()) {
                log.warn("FCM : aucun jeton d acces obtenu, notification non envoyee");
                return WebPushSender.Outcome.FAILED;
            }
            restClient.post()
                    .uri("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send")
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toMessageJson(token, content))
                    .retrieve()
                    .toBodilessEntity();
            return WebPushSender.Outcome.SENT;
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            String body = ex.getResponseBodyAsString();
            if (status.value() == 404 || body.contains("UNREGISTERED") || body.contains("INVALID_ARGUMENT")) {
                return WebPushSender.Outcome.GONE;
            }
            log.warn("FCM refuse ({}) : {}", status.value(), summarize(body));
            return WebPushSender.Outcome.FAILED;
        } catch (RuntimeException ex) {
            log.warn("FCM non envoye : {}", ex.toString());
            return WebPushSender.Outcome.FAILED;
        }
    }

    /**
     * Message FCM v1 : notification systeme (titre, corps) + donnees ({@code url}, {@code tag})
     * pour l application ; sur Android, le {@code tag} regroupe les notifications d un meme sujet.
     */
    String toMessageJson(String token, NotificationTemplates.Push content) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", content.title());
        notification.put("body", content.body());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", content.url() == null ? "/" : content.url());
        data.put("tag", content.tag() == null ? "ekuiseo" : content.tag());
        Map<String, Object> androidNotification = new LinkedHashMap<>();
        androidNotification.put("tag", data.get("tag"));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", notification);
        message.put("data", data);
        message.put("android", Map.of("priority", "high", "notification", androidNotification));
        message.put("apns", Map.of("payload", Map.of("aps", Map.of("sound", "default"))));
        try {
            return objectMapper.writeValueAsString(Map.of("message", message));
        } catch (IOException ex) {
            throw new IllegalStateException("Message FCM non serialisable", ex);
        }
    }

    private static String summarize(String body) {
        if (body == null) {
            return "-";
        }
        try {
            JsonNode node = new ObjectMapper().readTree(body);
            String message = node.path("error").path("message").asText("");
            return message.isEmpty() ? body.substring(0, Math.min(200, body.length())) : message;
        } catch (IOException ex) {
            return body.substring(0, Math.min(200, body.length()));
        }
    }
}
