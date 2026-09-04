package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse;
import bj.ekuiseo.api.dto.admin.AdminStatsResponse;
import bj.ekuiseo.api.dto.admin.StatsResponse;
import bj.ekuiseo.api.service.admin.AdminLiquidityService;
import bj.ekuiseo.api.service.admin.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Statistiques agregees (trajets, reservations, volume, revenus) et indicateurs de liquidite. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Statistiques", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;
    private final AdminLiquidityService adminLiquidityService;

    public AdminStatsController(AdminStatsService adminStatsService, AdminLiquidityService adminLiquidityService) {
        this.adminStatsService = adminStatsService;
        this.adminLiquidityService = adminLiquidityService;
    }

    @Operation(summary = "Indicateurs de liquidite sur les N derniers jours",
            description = "Metrique nord (places confirmees vs seuil de 2 000/mois), taux de recherche aboutie, recherche -> reservation, "
                    + "taux de remplissage par mode et par axe, trajets orphelins, delai median publication -> premiere reservation, "
                    + "axes en penurie. La periode precedente de meme duree est renvoyee sous la meme forme (current/previous).")
    @GetMapping("/liquidity")
    public AdminLiquidityResponse liquidity(@RequestParam(defaultValue = "30") int days) {
        return adminLiquidityService.compute(days);
    }

    @Operation(summary = "Export CSV des indicateurs de liquidite",
            description = "Memes chiffres que /liquidity, en CSV (separateur ';', decimales a la virgule, UTF-8 avec BOM : "
                    + "s'ouvre directement dans un tableur en francais).")
    @GetMapping(value = "/liquidity/export", produces = "text/csv")
    public ResponseEntity<byte[]> liquidityCsv(@RequestParam(defaultValue = "30") int days) {
        AdminLiquidityResponse response = adminLiquidityService.compute(days);
        byte[] body = adminLiquidityService.toCsv(response).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"liquidite-" + days + "j.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "Tableau de bord sur les N derniers jours", description = "Serie journaliere, totaux, variation vs la periode precedente de meme duree, et axes les plus demandes. Forme attendue par le front (extended.ts, AdminStatsResponse).")
    @GetMapping(params = "days")
    public AdminStatsResponse statsByDays(@RequestParam int days) {
        return adminStatsService.computeStats(days);
    }

    @Operation(summary = "Statistiques agregees sur une periode explicite (forme historique)", description = "from/to au format ISO-8601 (ex: 2026-09-01T00:00:00Z). Conserve pour compatibilite ; voir ?days=N pour le contrat front actuel.")
    @GetMapping(params = {"from", "to"})
    public StatsResponse statsByRange(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return adminStatsService.computeStats(from, to);
    }
}
