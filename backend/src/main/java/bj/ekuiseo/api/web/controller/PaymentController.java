package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Endpoint public (appele par les serveurs Kkiapay), securise par comparaison du
     * "secret hash" du tableau de bord Kkiapay plutot que par JWT (voir SecurityConfig :
     * permitAll sur cette route, et RateLimitingFilter pour la limitation de debit).
     * Le corps est recu brut pour etre hache et persiste AVANT traitement (contrat A.5) ; une
     * signature absente ou fausse est enregistree REJECTED et acquittee en 200 (jamais de rejeu
     * infini cote Kkiapay), un corps deja traite est enregistre DUPLICATE et ignore. L'etat de la
     * transaction n'est jamais deduit du seul payload : voir PaymentService#handleWebhook qui
     * reconfirme aupres de l'API Kkiapay.
     */
    @Operation(summary = "Webhook Kkiapay (usage interne agregateur)", description = "Appele par les serveurs Kkiapay, jamais par le frontend. Signature via l'en-tete X-Kkiapay-Secret ; corps persiste avant traitement, rejeu ignore.")
    @PostMapping("/webhook")
    public void webhook(@RequestBody(required = false) String rawBody,
                        @RequestHeader(value = "X-Kkiapay-Secret", required = false) String secretHeader) {
        paymentService.receiveWebhook(rawBody, secretHeader);
    }
}
