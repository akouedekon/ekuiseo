package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.payout.AdminPayoutResponse;
import bj.ekuiseo.api.dto.payout.FailPayoutRequest;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.dto.payout.SettlePayoutRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "Lister tous les reversements", description = "Forme back-office (driverName, provider, tripCount, reference de virement...), voir AdminPayoutResponse.")
    @GetMapping
    public List<AdminPayoutResponse> list() {
        return payoutService.listAllForAdmin();
    }

    @Operation(summary = "Declencher un lot de reversement", description = "Cree un DriverPayout PENDING par conducteur dont le solde atteint le seuil minimum (2000 FCFA par defaut). 409 si un lot est deja en cours de constitution.")
    @PostMapping("/run")
    public PayoutBatchResultResponse run() {
        return payoutService.runWeeklyBatch(currentUser.id());
    }

    @Operation(summary = "Marquer un reversement comme regle", description = "A appeler apres execution manuelle du virement mobile money (aucune API de decaissement Kkiapay confirmee, voir PayoutService). Corps optionnel : reference du virement et montant regle. Exige un lot PENDING ou FAILED (409 sinon). Le conducteur est prevenu (PAYOUT_SETTLED).")
    @PostMapping("/{id}/settle")
    public PayoutResponse settle(@PathVariable UUID id, @Valid @RequestBody(required = false) SettlePayoutRequest req) {
        return payoutService.settle(currentUser.id(), id,
                req == null ? null : req.externalReference(), req == null ? null : req.settledAmountFcfa());
    }

    @Operation(summary = "Marquer un reversement comme regle (alias historique)", description = "Equivalent de POST .../settle sans corps, conserve pour l ancien front.")
    @PostMapping("/{id}/pay")
    public PayoutResponse pay(@PathVariable UUID id) {
        return payoutService.settle(currentUser.id(), id);
    }

    @Operation(summary = "Marquer un virement en echec", description = "Le lot passe FAILED avec le motif ; il pourra etre regle plus tard (« Relancer » = settle depuis FAILED). Exige un lot PENDING ou PROCESSING (409 sinon). Le conducteur est prevenu (PAYOUT_FAILED).")
    @PostMapping("/{id}/fail")
    public PayoutResponse fail(@PathVariable UUID id, @Valid @RequestBody FailPayoutRequest req) {
        return payoutService.fail(currentUser.id(), id, req.reason());
    }
}
