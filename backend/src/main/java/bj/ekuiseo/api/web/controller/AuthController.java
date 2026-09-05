package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentification et inscription. Toutes les routes sont publiques (voir
 * SecurityConfig) mais soumises a limitation de debit (voir RateLimitingFilter,
 * 20 requetes/60s/IP par defaut).
 */
@Tag(name = "Authentification", description = "Inscription et connexion par code e-mail (OTP), rafraichissement de jeton. Aucun mot de passe : le parcours OTP est le seul.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Inscription par OTP", description = "Cree le compte (prenom, nom, e-mail obligatoire) sans mot de passe et envoie le code de connexion a l adresse e-mail. Renvoie le canal et la destination masquee. La session s ouvre ensuite via /otp/verify. 409 si le numero ou l e-mail est deja inscrit.")
    @PostMapping("/otp/register")
    public ResponseEntity<OtpRequestResponse> registerWithOtp(@Valid @RequestBody OtpRegisterRequest req) {
        return ResponseEntity.accepted().body(authService.registerWithOtp(req));
    }

    @Operation(summary = "Demander un code OTP", description = "Envoie un code a 6 chiffres a l adresse e-mail du compte (SMS en repli si configure), valable 5 minutes. Renvoie le canal et la destination masquee. 404 si le numero est inconnu, 401 si le compte est suspendu, 429 au-dela de 3 demandes/10 min par numero.")
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponse> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        return ResponseEntity.accepted().body(authService.requestOtp(req));
    }

    @Operation(summary = "Verifier un code OTP", description = "Valide le code et ouvre la session (marque l e-mail ou le numero comme verifie selon le canal). Le code est invalide au-dela de 5 tentatives incorrectes (configurable).")
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return authService.verifyOtp(req);
    }

    @Operation(summary = "Rafraichir les jetons", description = "Echange un refresh token valide contre une nouvelle paire de jetons.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }
}
