package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.dto.payment.InitiatePaymentRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Paiements Kkiapay", description = "Initiation cote widget frontend et reception du webhook Kkiapay")
@RestController
@RequestMapping("/api/v1/payments/kkiapay")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    public PaymentController(PaymentService paymentService, CurrentUser currentUser) {
        this.paymentService = paymentService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Preparer un paiement de reservation", description = "Renvoie la cle publique Kkiapay et les donnees a transmettre au widget frontend (voir InitiatePaymentResponse.widgetData). Le paiement lui-meme est initie par ce widget, pas par cet endpoint.")
    @PostMapping("/initiate")
    public InitiatePaymentResponse initiate(@Valid @RequestBody InitiatePaymentRequest req) {
        return paymentService.initiate(currentUser.id(), req);
    }

    /**
     * Endpoint public (appele par les serveurs Kkiapay), securise par comparaison du
     * "secret hash" du tableau de bord Kkiapay plutot que par JWT (voir SecurityConfig :
     * permitAll sur cette route, et RateLimitingFilter pour la limitation de debit).
     * L'etat de la transaction n'est jamais deduit du seul payload : voir
     * PaymentService#handleWebhook qui reconfirme aupres de l'API Kkiapay.
     */
    @Operation(summary = "Webhook Kkiapay (usage interne agregateur)", description = "Appele par les serveurs Kkiapay, jamais par le frontend. Signature obligatoire via l'en-tete X-Kkiapay-Secret.")
    @PostMapping("/webhook")
    public void webhook(@RequestBody KkiapayWebhookPayload payload,
                         @RequestHeader(value = "X-Kkiapay-Secret", required = false) String secretHeader) {
        if (!paymentService.verifySignature(secretHeader)) {
            throw new BadRequestException("Signature de webhook invalide");
        }
        paymentService.handleWebhook(payload);
    }
}
