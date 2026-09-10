package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.payout.DriverEarningsResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.DriverEarningsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Revenus du conducteur connecte (contrat A.8). */
@Tag(name = "Revenus (conducteur)", description = "Solde, en attente, en cours de reversement, regle, especes, six derniers mois")
@RestController
@RequestMapping("/api/v1/me/earnings")
public class MeEarningsController {

    private final DriverEarningsService driverEarningsService;
    private final CurrentUser currentUser;

    public MeEarningsController(DriverEarningsService driverEarningsService, CurrentUser currentUser) {
        this.driverEarningsService = driverEarningsService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mes revenus de conducteur", description = "Calcul agrege en base : solde reversable, en attente d eligibilite (24 h apres le depart), lots en cours, total regle, chiffre d affaires, commission, especes reglees a bord, trajets, note, prochain lot, compte mobile money verifie, detail par mois.")
    @GetMapping
    public DriverEarningsResponse earnings() {
        return driverEarningsService.compute(currentUser.id());
    }
}
