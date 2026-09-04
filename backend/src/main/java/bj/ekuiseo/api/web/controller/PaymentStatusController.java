package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.payment.PaymentStatusResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Etat d'un paiement pour sondage cote front en attendant le webhook Kkiapay
 * (voir PaymentService#getStatus). Distinct de /api/v1/payments/kkiapay/** (qui
 * concerne uniquement l'initiation et le webhook de l'agregateur) : ce
 * sous-chemin est reserve au passager proprietaire du paiement.
 */
@Tag(name = "Paiements", description = "Consultation de l'etat d'un paiement (sondage cote passager)")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentStatusController {

    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    public PaymentStatusController(PaymentService paymentService, CurrentUser currentUser) {
        this.paymentService = paymentService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Etat d'un paiement", description = "Reserve au passager de la reservation concernee. A sonder toutes les quelques secondes en attendant le webhook Kkiapay.")
    @GetMapping("/{paymentId}")
    public PaymentStatusResponse getStatus(@PathVariable UUID paymentId) {
        return paymentService.getStatus(paymentId, currentUser.id());
    }
}
