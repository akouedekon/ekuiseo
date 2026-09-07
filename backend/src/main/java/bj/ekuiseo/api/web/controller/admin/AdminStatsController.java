package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminLiquidityResponse;
import bj.ekuiseo.api.dto.admin.AdminRetentionResponse;
import bj.ekuiseo.api.dto.admin.AdminStatsResponse;
import bj.ekuiseo.api.service.admin.AdminLiquidityService;
import bj.ekuiseo.api.service.admin.AdminRetentionService;
import bj.ekuiseo.api.service.admin.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Statistiques agregees (trajets, reservations, volume, revenus), indicateurs de liquidite et de retention. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Statistiques", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;
    private final AdminLiquidityService adminLiquidityService;
    private final AdminRetentionService adminRetentionService;

    public AdminStatsController(AdminStatsService adminStatsService, AdminLiquidityService adminLiquidityService,
                                AdminRetentionService adminRetentionService) {
        this.adminStatsService = adminStatsService;
        this.adminLiquidityService = adminLiquidityService;
        this.adminRetentionService = adminRetentionService;
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
        return csv(adminLiquidityService.toCsv(response), "liquidite-" + days + "j.csv");
    }

    @Operation(summary = "Indicateurs de retention et de paiement sur les N derniers jours",
            description = "Retention conducteur (W1/W4) et passager (30 j), part du mode quotidien, navettes actives et "
                    + "remplissage des occurrences, conversion reservation -> acompte, part expiree, echecs Kkiapay par "
                    + "operateur reel, repartition des modes de paiement, panier moyen et places par reservation. Taux en "
                    + "fraction 0..1, null quand le denominateur est nul ; previous porte les memes scalaires sur la periode precedente.")
    @GetMapping("/retention")
    public AdminRetentionResponse retention(@RequestParam(defaultValue = "30") int days) {
        return adminRetentionService.compute(days);
    }

    @Operation(summary = "Export CSV des indicateurs de retention et de paiement",
            description = "Memes chiffres que /retention, en CSV (separateur ';', decimales a la virgule, UTF-8 avec BOM).")
    @GetMapping(value = "/retention/export", produces = "text/csv")
    public ResponseEntity<byte[]> retentionCsv(@RequestParam(defaultValue = "30") int days) {
        AdminRetentionResponse response = adminRetentionService.compute(days);
        return csv(adminRetentionService.toCsv(response), "retention-" + days + "j.csv");
    }

    @Operation(summary = "Tableau de bord sur les N derniers jours", description = "Serie journaliere, totaux, variation vs la periode precedente de meme duree, et axes les plus demandes. Forme attendue par le front (extended.ts, AdminStatsResponse).")
    @GetMapping(params = "days")
    public AdminStatsResponse statsByDays(@RequestParam int days) {
        return adminStatsService.computeStats(days);
    }

    private static ResponseEntity<byte[]> csv(String content, String fileName) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }
}
