package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reception du webhook Kkiapay. L initiation d un paiement de reservation passe par
 * {@code POST /api/v1/bookings/{id}/payments/deposit} (BookingController) : l ancien alias
 * {@code /initiate}, jamais appele par le front, a ete retire (constat F015).
 */
@Tag(name = "Paiements Kkiapay", description = "Reception du webhook Kkiapay (usage interne agregateur)")
@RestController
@RequestMapping("/api/v1/payments/kkiapay")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Endpoint public (appele par les serveurs Kkiapay), securise par comparaison du
     * "secret hash" du tableau de bord Kkiapay plutot que par JWT (voir SecurityConfig :
     * permitAll sur cette route, et RateLimitingFilter pour la limitation de debit).
     * Une signature absente ou fausse est un etat terminal cote Kkiapay : 401 journalise,
     * jamais 400 (constat F149). L'etat de la transaction n'est jamais deduit du seul
     * payload : voir PaymentService#handleWebhook qui reconfirme aupres de l'API Kkiapay.
     */
    @Operation(summary = "Webhook Kkiapay (usage interne agregateur)", description = "Appele par les serveurs Kkiapay, jamais par le frontend. Signature obligatoire via l'en-tete X-Kkiapay-Secret (401 sinon).")
    @PostMapping("/webhook")
    public void webhook(@RequestBody KkiapayWebhookPayload payload,
                         @RequestHeader(value = "X-Kkiapay-Secret", required = false) String secretHeader) {
        if (!paymentService.verifySignature(secretHeader)) {
            log.warn("Webhook Kkiapay refuse : signature {} (transactionId={})",
                    secretHeader == null || secretHeader.isBlank() ? "absente" : "invalide",
                    payload == null ? null : payload.transactionId());
            throw new UnauthorizedException("Signature de webhook invalide");
        }
        paymentService.handleWebhook(payload);
    }
}
