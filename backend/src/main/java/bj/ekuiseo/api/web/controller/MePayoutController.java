package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.payout.DriverBalanceResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Solde et historique de reversement du conducteur connecte (regle metier n.12). */
@Tag(name = "Reversements (conducteur)", description = "Solde non reverse et historique des lots regles")
@RestController
@RequestMapping("/api/v1/me/payouts")
public class MePayoutController {

    private final PayoutService payoutService;
    private final CurrentUser currentUser;

    public MePayoutController(PayoutService payoutService, CurrentUser currentUser) {
        this.payoutService = payoutService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mon solde en attente de reversement")
    @GetMapping("/balance")
    public DriverBalanceResponse balance() {
        return payoutService.getBalance(currentUser.id());
    }

    @Operation(summary = "Historique de mes reversements")
    @GetMapping
    public List<PayoutResponse> history() {
        return payoutService.history(currentUser.id());
    }
}
