package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.subscription.SubscriptionResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Abonnement conducteur (regle metier n.11 : 2000 FCFA/mois, commission ramenee a 0%). */
@Tag(name = "Abonnement conducteur", description = "Souscription mensuelle exonerant de commission de service")
@RestController
@RequestMapping("/api/v1/me/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final CurrentUser currentUser;

    public SubscriptionController(SubscriptionService subscriptionService, CurrentUser currentUser) {
        this.subscriptionService = subscriptionService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Statut de mon abonnement")
    @GetMapping
    public SubscriptionResponse getStatus() {
        return subscriptionService.getStatus(currentUser.id());
    }

    @Operation(summary = "Souscrire un abonnement", description = "Cree un abonnement PENDING_PAYMENT et renvoie ce qu'il faut pour ouvrir le widget de paiement Kkiapay.")
    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> subscribe() {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.subscribe(currentUser.id()));
    }
}
