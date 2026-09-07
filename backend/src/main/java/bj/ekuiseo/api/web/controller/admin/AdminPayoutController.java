package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.PayoutStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Declenchement et suivi des lots de reversement conducteurs (regle metier n.12). Reserve a
 * ROLE_ADMIN. L alias historique {@code POST /{id}/pay}, jamais appele par le front, a ete
 * retire (constat F015) : le reglement passe par {@code /settle}, avec ou sans corps.
 */
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

    @Operation(summary = "Lister les reversements", description = "Forme back-office (driverName, provider, tripCount, reference de virement...), voir AdminPayoutResponse. Page Spring (content, totalElements, number, size, last), plus recents d abord, size <= 100, filtrable par statut (PENDING/PROCESSING/SETTLED/FAILED).")
    @GetMapping
    public Page<AdminPayoutResponse> list(@RequestParam(required = false) PayoutStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return payoutService.listAllForAdmin(status, page, size);
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

    @Operation(summary = "Marquer un virement en echec", description = "Le lot passe FAILED avec le motif ; il pourra etre regle plus tard (« Relancer » = settle depuis FAILED). Exige un lot PENDING ou PROCESSING (409 sinon). Le conducteur est prevenu (PAYOUT_FAILED).")
    @PostMapping("/{id}/fail")
    public PayoutResponse fail(@PathVariable UUID id, @Valid @RequestBody FailPayoutRequest req) {
        return payoutService.fail(currentUser.id(), id, req.reason());
    }
}
