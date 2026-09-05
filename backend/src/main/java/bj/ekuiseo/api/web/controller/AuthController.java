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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inscription et connexion par code e-mail (OTP), rafraichissement et deconnexion.
 * Toutes les routes sont publiques (voir SecurityConfig) mais soumises a limitation de
 * debit (RateLimitingFilter : 20 requetes/60 s/IP sur /auth/**, plus 10 demandes de
 * code / 10 min / IP sur /otp/request et /otp/register). Aucun mot de passe : le
 * parcours OTP est le seul.
 */
@Tag(name = "Authentification", description = "Inscription et connexion par code e-mail (OTP), rafraichissement et deconnexion. Aucun mot de passe.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Inscription par OTP", description = "Cree le compte en attente de verification (prenom, nom, e-mail obligatoire, numero E.164 ou beninois a 10 chiffres) et envoie le code de connexion a l adresse e-mail. Renvoie le canal et la destination masquee. La session s ouvre ensuite via /otp/verify, qui active le compte. 409 si le numero (deja verifie) ou l e-mail est deja inscrit ; un numero jamais verifie est repris.")
    @PostMapping("/otp/register")
    public ResponseEntity<OtpRequestResponse> registerWithOtp(@Valid @RequestBody OtpRegisterRequest req) {
        return ResponseEntity.accepted().body(authService.registerWithOtp(req));
    }

    @Operation(summary = "Demander un code", description = "Envoie un code a 6 chiffres a l adresse e-mail du compte (SMS en repli si configure), valable 5 minutes. Renvoie le canal et la destination masquee. 404 si le numero est inconnu, 401 si le compte est suspendu, 429 au-dela de 3 demandes/10 min par numero.")
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponse> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        return ResponseEntity.accepted().body(authService.requestOtp(req));
    }

    @Operation(summary = "Verifier un code", description = "Valide le code et ouvre la session (active un compte en attente, marque l e-mail ou le numero comme verifie selon le canal). Le code est invalide au-dela de 5 tentatives incorrectes.")
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return authService.verifyOtp(req);
    }

    @Operation(summary = "Rafraichir les jetons", description = "Echange un refresh token valide contre une nouvelle paire (rotation : l ancien est revoque). Un refresh deja utilise revoque toute la chaine (401), une session ne depasse jamais 90 jours.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }

    @Operation(summary = "Se deconnecter", description = "Revoque le refresh token presente et toute sa chaine de rotation. Toujours 204, meme si le jeton est deja invalide.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest req) {
        authService.logout(req == null ? null : req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
