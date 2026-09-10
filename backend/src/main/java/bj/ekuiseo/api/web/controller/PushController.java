package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.push.VapidPublicKeyResponse;
import bj.ekuiseo.api.service.push.FcmSender;
import bj.ekuiseo.api.service.push.WebPushSender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cle publique VAPID du serveur (Web Push, V20). Publique : elle est par nature connue de
 * chaque navigateur abonne. 204 quand le push est desactive (cles absentes), pour que le
 * front masque l interrupteur sans erreur. Ouverte sans jeton par {@code PushSecurityConfig}.
 */
@Tag(name = "Web Push", description = "Cle publique VAPID")
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    private final WebPushSender webPushSender;
    private final FcmSender fcmSender;

    public PushController(WebPushSender webPushSender, FcmSender fcmSender) {
        this.webPushSender = webPushSender;
        this.fcmSender = fcmSender;
    }

    /** Canaux push actifs sur ce serveur (V24) : l application native sait ainsi si un jeton FCM sera servi. */
    public record PushConfigResponse(boolean webPush, boolean nativePush) {
    }

    @Operation(summary = "Canaux push actifs", description = "webPush : cles VAPID presentes ; nativePush : compte de service Firebase present.")
    @GetMapping("/config")
    public PushConfigResponse config() {
        return new PushConfigResponse(webPushSender.isEnabled(), fcmSender.isEnabled());
    }

    @Operation(summary = "Cle publique VAPID", description = "204 si le push est desactive sur ce serveur.")
    @GetMapping("/vapid-public-key")
    public ResponseEntity<VapidPublicKeyResponse> vapidPublicKey() {
        String key = webPushSender.publicKey();
        return key == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(new VapidPublicKeyResponse(key));
    }
}
