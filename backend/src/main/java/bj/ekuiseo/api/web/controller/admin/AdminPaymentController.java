package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.payment.PaymentEventResponse;
import bj.ekuiseo.api.dto.payment.WebhookEventResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PaymentEventService;
import bj.ekuiseo.api.service.PaymentWebhookService;
import bj.ekuiseo.api.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File des paiements a suivre (remboursements, routes historiques conservees et alimentees depuis
 * {@code refunds}), evenements d un paiement et webhooks recus (contrat A.5). Reserve a ROLE_ADMIN.
 */
@Tag(name = "Admin - Paiements", description = "Remboursements en attente, manuels et effectues ; evenements et webhooks (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {

    private final RefundService refundService;
    private final PaymentEventService paymentEventService;
    private final PaymentWebhookService paymentWebhookService;
    private final CurrentUser currentUser;

    public AdminPaymentController(RefundService refundService, PaymentEventService paymentEventService,
                                  PaymentWebhookService paymentWebhookService, CurrentUser currentUser) {
        this.refundService = refundService;
        this.paymentEventService = paymentEventService;
        this.paymentWebhookService = paymentWebhookService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Paiements a suivre", description = "status = REFUND_PENDING | REFUND_MANUAL | REFUNDED | ALL ; par defaut les deux premiers (la file de travail). Alimente depuis la table refunds (V26).")
    @GetMapping
    public List<AdminPaymentResponse> list(@RequestParam(required = false) String status) {
        return refundService.listForAdmin(status);
    }

    @Operation(summary = "Webhooks recus", description = "Chaque webhook persiste avant traitement (hash du corps, signature, issue : PROCESSED, DUPLICATE, IGNORED, REJECTED, ERROR). Page Spring, plus recents d abord.")
    @GetMapping("/webhooks")
    public Page<WebhookEventResponse> webhooks(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return paymentWebhookService.list(page, size);
    }

    @Operation(summary = "Evenements d un paiement", description = "Chaque transition ou tentative (initiation, verification widget/webhook, rejeu ignore, abandon, remboursement), plus anciens d abord.")
    @GetMapping("/{id}/events")
    public List<PaymentEventResponse> events(@PathVariable UUID id) {
        return paymentEventService.listForPayment(id);
    }

    @Operation(summary = "Relancer un remboursement", description = "Rejoue l appel au fournisseur tout de suite sur le remboursement vivant de ce paiement et renvoie l etat obtenu. Refuse un montant partiel ou un paiement sans identifiant du fournisseur.")
    @PostMapping("/{id}/refund")
    public AdminPaymentResponse retry(@PathVariable UUID id) {
        return refundService.retryNow(currentUser.id(), id);
    }

    @Operation(summary = "Marquer rembourse", description = "Le remboursement a ete fait a la main (tableau de bord du fournisseur, virement) : journalise et previent le passager.")
    @PostMapping("/{id}/mark-refunded")
    public AdminPaymentResponse markRefunded(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return refundService.markRefunded(currentUser.id(), id, body == null ? null : body.get("note"));
    }
}
