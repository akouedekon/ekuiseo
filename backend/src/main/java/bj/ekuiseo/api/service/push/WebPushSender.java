package bj.ekuiseo.api.service.push;

import bj.ekuiseo.api.service.NotificationTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Envoi Web Push (VAPID, RFC 8292 ; chiffrement aes128gcm, RFC 8291) via la bibliotheque
 * {@code nl.martijndwars:web-push}. Cles VAPID lues dans {@code ekuiseo.push.vapid.*} :
 * vides, le canal est simplement desactive (aucune exception au demarrage, l application
 * fonctionne sans push) ; invalides, il est desactive aussi, avec une erreur journalisee.
 *
 * <p>Le contenu envoye est un petit JSON {@code {title, body, url, tag}} lu par le service
 * worker ({@code src/sw.ts}) : jamais de donnee personnelle au-dela du texte de la
 * notification, deja connu du destinataire.</p>
 */
@Service
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    /** Duree de vie d une notification chez le service push si l appareil est hors ligne. */
    static final int TTL_SECONDS = 24 * 60 * 60;

    /** Resultat d un envoi, interprete par NotificationDispatcher. */
    public enum Outcome {
        /** Accepte par le service push (201/200). */
        SENT,
        /** Abonnement expire ou revoque (404/410) : a supprimer. */
        GONE,
        /** Autre echec (reseau, 4xx/5xx) : journalise, abonnement conserve. */
        FAILED,
        /** Push desactive (cles absentes). */
        DISABLED
    }

    private final PushService pushService;
    private final String publicKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebPushSender(@Value("${ekuiseo.push.vapid.public-key:}") String publicKey,
                         @Value("${ekuiseo.push.vapid.private-key:}") String privateKey,
                         @Value("${ekuiseo.push.subject:mailto:contact@ekuiseo.com}") String subject) {
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.pushService = build(this.publicKey, privateKey == null ? "" : privateKey.trim(), subject);
    }

    private static PushService build(String publicKey, String privateKey, String subject) {
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            log.info("Web Push desactive : PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY non renseignees");
            return null;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            PushService service = new PushService(publicKey, privateKey, subject);
            log.info("Web Push actif (sujet VAPID {})", subject);
            return service;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            log.error("Web Push desactive : cles VAPID invalides ({})", ex.getMessage());
            return null;
        }
    }

    public boolean isEnabled() {
        return pushService != null;
    }

    /** Cle publique VAPID (base64url), transmise au navigateur pour {@code pushManager.subscribe}. */
    public String publicKey() {
        return isEnabled() ? publicKey : null;
    }

    /**
     * Envoie une notification a un abonnement. Ne leve jamais : tout echec est traduit en
     * {@link Outcome} et journalise sans l endpoint complet (il vaut identifiant d appareil).
     */
    public Outcome send(String endpoint, String p256dh, String auth, NotificationTemplates.Push content) {
        if (!isEnabled()) {
            return Outcome.DISABLED;
        }
        try {
            Notification notification = Notification.builder()
                    .endpoint(endpoint)
                    .userPublicKey(p256dh)
                    .userAuth(auth)
                    .payload(toJson(content).getBytes(StandardCharsets.UTF_8))
                    .ttl(TTL_SECONDS)
                    .build();
            HttpResponse response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                return Outcome.GONE;
            }
            if (status >= 200 && status < 300) {
                return Outcome.SENT;
            }
            log.warn("Web Push refuse ({}) par {}", status, origin(endpoint));
            return Outcome.FAILED;
        } catch (Exception ex) {
            // GeneralSecurityException (cles du navigateur invalides), IOException, JoseException,
            // ExecutionException, InterruptedException : meme traitement, l appelant conserve l abonnement.
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Web Push non envoye a {} : {}", origin(endpoint), ex.toString());
            return Outcome.FAILED;
        }
    }

    String toJson(NotificationTemplates.Push content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", content.title());
        payload.put("body", content.body());
        payload.put("url", content.url());
        payload.put("tag", content.tag());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Serialisation du contenu push impossible", ex);
        }
    }

    /** Origine seule de l endpoint pour les journaux : le chemin identifie l appareil. */
    static String origin(String endpoint) {
        if (endpoint == null) return "?";
        int scheme = endpoint.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = endpoint.indexOf('/', start);
        return slash < 0 ? endpoint : endpoint.substring(0, slash);
    }
}
