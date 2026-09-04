package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.LoginRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.dto.auth.RegisterRequest;
import bj.ekuiseo.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentification et inscription. Toutes les routes sont publiques (voir
 * SecurityConfig) mais soumises a limitation de debit (voir RateLimitingFilter,
 * 20 requetes/60s/IP par defaut).
 */
@Tag(name = "Authentification", description = "Inscription, connexion, verification OTP et rafraichissement de jeton")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Inscription", description = "Cree un compte et renvoie immediatement une paire de jetons (access + refresh). Le numero doit etre au format E.164.")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @Operation(summary = "Demander un code OTP", description = "Envoie un code a 6 chiffres par SMS, valable 5 minutes. Limite a 3 demandes/10 min par numero (429 au-dela).")
    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        authService.requestOtp(req);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Verifier un code OTP", description = "Confirme le numero de telephone. Le code est invalide au-dela de 5 tentatives incorrectes (configurable).")
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return authService.verifyOtp(req);
    }

    @Operation(summary = "Connexion", description = "Authentification par numero de telephone et mot de passe.")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @Operation(summary = "Rafraichir les jetons", description = "Echange un refresh token valide contre une nouvelle paire de jetons.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }
}
