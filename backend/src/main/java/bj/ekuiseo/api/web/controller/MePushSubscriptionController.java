package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.push.PushSubscriptionRequest;
import bj.ekuiseo.api.dto.push.PushUnsubscribeRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Abonnements Web Push du navigateur courant (V20) : enregistrement apres {@code pushManager.subscribe}, retrait. */
@Tag(name = "Mes abonnements push", description = "Web Push : enregistrement et retrait de l abonnement du navigateur")
@RestController
@RequestMapping("/api/v1/me/push-subscriptions")
public class MePushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;
    private final CurrentUser currentUser;

    public MePushSubscriptionController(PushSubscriptionService pushSubscriptionService, CurrentUser currentUser) {
        this.pushSubscriptionService = pushSubscriptionService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Enregistrer l abonnement push de ce navigateur", description = "Upsert par endpoint ; 3 appareils au plus par compte (le plus ancien est remplace).")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@Valid @RequestBody PushSubscriptionRequest req,
                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        pushSubscriptionService.subscribe(currentUser.id(), req, userAgent);
    }

    @Operation(summary = "Retirer l abonnement push de ce navigateur", description = "Idempotent : un endpoint inconnu renvoie aussi 204.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@Valid @RequestBody PushUnsubscribeRequest req) {
        pushSubscriptionService.unsubscribe(currentUser.id(), req.endpoint());
    }
}
