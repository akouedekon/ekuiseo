package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.payout.AdminPaymentAccountResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PaymentAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Comptes mobile money des utilisateurs : verification manuelle de possession avant reversement. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Comptes mobile money", description = "Verification des comptes de reversement (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/payment-accounts")
public class AdminPaymentAccountController {

    private final PaymentAccountService paymentAccountService;
    private final CurrentUser currentUser;

    public AdminPaymentAccountController(PaymentAccountService paymentAccountService, CurrentUser currentUser) {
        this.paymentAccountService = paymentAccountService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Comptes mobile money", description = "verified=false (defaut) : comptes en attente de verification ; verified=true : comptes verifies ; sans filtre : tous.")
    @GetMapping
    public List<AdminPaymentAccountResponse> list(@RequestParam(required = false) Boolean verified) {
        return paymentAccountService.listForAdmin(verified);
    }

    @Operation(summary = "Verifier un compte", description = "Atteste que la possession du numero a ete etablie hors ligne (appel, rappel, piece). Journalise ; le compte devient eligible aux reversements.")
    @PostMapping("/{id}/verify")
    public AdminPaymentAccountResponse verify(@PathVariable UUID id) {
        return paymentAccountService.verifyByAdmin(currentUser.id(), id);
    }
}
