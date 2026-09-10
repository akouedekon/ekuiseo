package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.payment.AdminRefundResponse;
import bj.ekuiseo.api.dto.payment.FailRefundRequest;
import bj.ekuiseo.api.dto.payment.MarkRefundSucceededRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Remboursements (contrat A.3, V26) : file, relance, marquage manuel, abandon. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Remboursements", description = "Machine d etat des remboursements (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/refunds")
public class AdminRefundController {

    private final RefundService refundService;
    private final CurrentUser currentUser;

    public AdminRefundController(RefundService refundService, CurrentUser currentUser) {
        this.refundService = refundService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Lister les remboursements", description = "Page Spring, plus recents d abord, filtrable par statut (REQUESTED, PROCESSING, SUCCEEDED, FAILED, MANUAL_REVIEW).")
    @GetMapping
    public Page<AdminRefundResponse> list(@RequestParam(required = false) RefundStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return refundService.listRefundsForAdmin(status, page, size);
    }

    @Operation(summary = "Relancer un remboursement", description = "FAILED ou MANUAL_REVIEW -> PROCESSING tout de suite ; l etat obtenu est renvoye. Refuse un montant partiel ou un paiement sans reference du fournisseur (409 si deja effectue ou en cours).")
    @PostMapping("/{id}/retry")
    public AdminRefundResponse retry(@PathVariable UUID id) {
        return refundService.retry(currentUser.id(), id);
    }

    @Operation(summary = "Marquer effectue", description = "Le remboursement a ete fait a la main : SUCCEEDED avec la reference, paiement REFUNDED, ecritures au registre, passager prevenu.")
    @PostMapping("/{id}/mark-succeeded")
    public AdminRefundResponse markSucceeded(@PathVariable UUID id, @Valid @RequestBody(required = false) MarkRefundSucceededRequest req) {
        return refundService.markSucceeded(currentUser.id(), id, req == null ? null : req.providerReference());
    }

    @Operation(summary = "Abandonner un remboursement", description = "FAILED definitif avec motif ; le paiement redevient SUCCEEDED (l argent reste encaisse). Decision exceptionnelle, journalisee.")
    @PostMapping("/{id}/fail")
    public AdminRefundResponse fail(@PathVariable UUID id, @Valid @RequestBody FailRefundRequest req) {
        return refundService.fail(currentUser.id(), id, req.reason());
    }
}
