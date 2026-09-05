package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** File des paiements a suivre (remboursements). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Paiements", description = "Remboursements en attente, manuels et effectues (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {

    private final RefundService refundService;
    private final CurrentUser currentUser;

    public AdminPaymentController(RefundService refundService, CurrentUser currentUser) {
        this.refundService = refundService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Paiements a suivre", description = "status = REFUND_PENDING | REFUND_MANUAL | REFUNDED | ALL ; par defaut les deux premiers (la file de travail).")
    @GetMapping
    public List<AdminPaymentResponse> list(@RequestParam(required = false) String status) {
        return refundService.listForAdmin(status);
    }

    @Operation(summary = "Relancer un remboursement", description = "Rejoue l appel Kkiapay tout de suite et renvoie l etat obtenu. Refuse un montant partiel ou un paiement sans identifiant Kkiapay (a traiter depuis le tableau de bord Kkiapay).")
    @PostMapping("/{id}/refund")
    public AdminPaymentResponse retry(@PathVariable UUID id) {
        return refundService.retryNow(currentUser.id(), id);
    }

    @Operation(summary = "Marquer rembourse", description = "Le remboursement a ete fait a la main (tableau de bord Kkiapay, virement) : journalise et previent le passager.")
    @PostMapping("/{id}/mark-refunded")
    public AdminPaymentResponse markRefunded(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return refundService.markRefunded(currentUser.id(), id, body == null ? null : body.get("note"));
    }
}
