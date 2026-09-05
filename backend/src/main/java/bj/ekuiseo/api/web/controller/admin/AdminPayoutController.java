package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.payout.AdminPayoutResponse;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Declenchement et suivi des lots de reversement conducteurs (regle metier n.12). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Reversements", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminPayoutController {

    private final PayoutService payoutService;
    private final CurrentUser currentUser;

    public AdminPayoutController(PayoutService payoutService, CurrentUser currentUser) {
        this.payoutService = payoutService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Lister tous les reversements", description = "Forme back-office (driverName, provider, tripCount...), voir AdminPayoutResponse.")
    @GetMapping
    public List<AdminPayoutResponse> list() {
        return payoutService.listAllForAdmin();
    }

    @Operation(summary = "Declencher un lot de reversement", description = "Cree un DriverPayout PENDING par conducteur dont le solde atteint le seuil minimum (2000 FCFA par defaut).")
    @PostMapping("/run")
    public PayoutBatchResultResponse run() {
        return payoutService.runWeeklyBatch(currentUser.id());
    }

    @Operation(summary = "Marquer un reversement comme regle", description = "A appeler apres execution manuelle du virement mobile money (aucune API de decaissement Kkiapay confirmee, voir PayoutService).")
    @PostMapping("/{id}/pay")
    public PayoutResponse pay(@PathVariable UUID id) {
        return payoutService.settle(currentUser.id(), id);
    }
}
