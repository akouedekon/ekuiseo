package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.user.IdentityVerificationResponse;
import bj.ekuiseo.api.dto.user.SubmitIdentityRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.IdentityVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verification d'identite de l'utilisateur connecte (regle metier n.19).
 * Le televersement de la photo du document reste un TODO documente (voir
 * IdentityVerification, README "Ce qui reste a faire") : seul le numero du
 * document est enregistre.
 */
@Tag(name = "Mon identite", description = "Depot et etat de la verification d'identite")
@RestController
@RequestMapping("/api/v1/me/identity")
public class MeIdentityController {

    private final IdentityVerificationService identityVerificationService;
    private final CurrentUser currentUser;

    public MeIdentityController(IdentityVerificationService identityVerificationService, CurrentUser currentUser) {
        this.identityVerificationService = identityVerificationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Etat de ma verification d'identite")
    @GetMapping
    public IdentityVerificationResponse get() {
        return identityVerificationService.get(currentUser.id());
    }

    @Operation(summary = "Soumettre ma verification d'identite", description = "Une nouvelle soumission remplace la precedente et repasse au statut PENDING.")
    @PostMapping
    public IdentityVerificationResponse submit(@Valid @RequestBody SubmitIdentityRequest req) {
        return identityVerificationService.submit(currentUser.id(), req);
    }
}
