package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.user.AddPaymentMethodRequest;
import bj.ekuiseo.api.dto.user.PaymentMethodResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.PaymentAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Comptes mobile money enregistres par l'utilisateur connecte (regle metier n.18). */
@Tag(name = "Mes moyens de paiement", description = "Comptes mobile money (operateur + numero), un seul par defaut")
@RestController
@RequestMapping("/api/v1/me/payment-methods")
public class MePaymentMethodController {

    private final PaymentAccountService paymentAccountService;
    private final CurrentUser currentUser;

    public MePaymentMethodController(PaymentAccountService paymentAccountService, CurrentUser currentUser) {
        this.paymentAccountService = paymentAccountService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mes moyens de paiement")
    @GetMapping
    public List<PaymentMethodResponse> list() {
        return paymentAccountService.list(currentUser.id());
    }

    @Operation(summary = "Ajouter un moyen de paiement", description = "Le premier ajoute devient automatiquement le moyen de paiement par defaut.")
    @PostMapping
    public ResponseEntity<PaymentMethodResponse> add(@Valid @RequestBody AddPaymentMethodRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentAccountService.add(currentUser.id(), req));
    }

    @Operation(summary = "Supprimer un moyen de paiement", description = "Si celui supprime etait le defaut, le plus ancien restant est promu automatiquement.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        paymentAccountService.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
