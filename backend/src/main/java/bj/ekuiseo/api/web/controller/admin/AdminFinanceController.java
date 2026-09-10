package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.dto.finance.FinanceSummaryResponse;
import bj.ekuiseo.api.dto.finance.LedgerAdjustmentRequest;
import bj.ekuiseo.api.dto.finance.LedgerEntryResponse;
import bj.ekuiseo.api.dto.finance.ReconciliationAnomalyResponse;
import bj.ekuiseo.api.dto.finance.ReconciliationRunRequest;
import bj.ekuiseo.api.dto.finance.ReconciliationRunResponse;
import bj.ekuiseo.api.dto.finance.ResolveAnomalyRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.LedgerService;
import bj.ekuiseo.api.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Registre financier et rapprochement (contrats A.4 et A.7). Reserve a ROLE_ADMIN ; toute action journalisee. */
@Tag(name = "Admin - Finance", description = "Registre financier, synthese, corrections et rapprochement (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/finance")
public class AdminFinanceController {

    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final CurrentUser currentUser;

    public AdminFinanceController(LedgerService ledgerService, ReconciliationService reconciliationService,
                                  CurrentUser currentUser) {
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Synthese financiere", description = "Totaux du registre sur les N derniers jours (encaisse, frais, commission nette, part conducteur, rembourse, reverse, especes), reversements dus, anomalies et remboursements ouverts, detail par mois.")
    @GetMapping("/summary")
    public FinanceSummaryResponse summary(@RequestParam(defaultValue = "30") int days) {
        return ledgerService.summary(days);
    }

    @Operation(summary = "Rechercher dans le registre", description = "Filtres facultatifs : bookingId, userId, entryType, from, to (ISO 8601, to exclusif). Page Spring, plus recentes d abord.")
    @GetMapping("/ledger")
    public Page<LedgerEntryResponse> ledger(@RequestParam(required = false) UUID bookingId,
                                            @RequestParam(required = false) UUID userId,
                                            @RequestParam(required = false) LedgerEntryType entryType,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return ledgerService.search(new LedgerService.Filter(bookingId, userId, entryType, from, to), page, size);
    }

    @Operation(summary = "Export CSV du registre", description = "from / to : instants ISO 8601 ou dates AAAA-MM-JJ (to exclusif ; 30 derniers jours par defaut). Separateur ';', UTF-8 avec BOM, montants entiers.")
    @GetMapping(value = "/ledger/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        Instant start = parseInstant(from, false);
        Instant end = parseInstant(to, true);
        String content = ledgerService.exportCsv(start, end);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"registre-financier.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Correction d administration", description = "Ecriture ADJUSTMENT : compte, sens, montant, description obligatoire. Journalisee (LEDGER_ADJUSTMENT). Le registre etant en ajout seul, c est la seule facon de corriger.")
    @PostMapping("/ledger/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse adjust(@Valid @RequestBody LedgerAdjustmentRequest req) {
        return ledgerService.adjust(currentUser.id(), req);
    }

    /* ------------------------------------------------------------------ rapprochement */

    @Operation(summary = "Lancer un rapprochement", description = "Re-verifie aupres du fournisseur chaque paiement des N derniers jours (7 par defaut) et l equilibre du registre ; renvoie l execution. Quota : 5 par minute.")
    @PostMapping("/reconciliation/run")
    public ReconciliationRunResponse run(@Valid @RequestBody(required = false) ReconciliationRunRequest req) {
        return reconciliationService.run(currentUser.id(), req == null || req.days() == null ? 7 : req.days());
    }

    @Operation(summary = "Importer l export CSV du fournisseur", description = "Multipart « file » : colonnes reconnues par en-tete (transactionId, amount, status ; en-tetes francais acceptes, montants « 1 000 » toleres). Anomalies : UNKNOWN_AT_PROVIDER, DUPLICATE_PROVIDER_TX, AMOUNT_MISMATCH, STATUS_MISMATCH.")
    @PostMapping(value = "/reconciliation/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconciliationRunResponse importCsv(@RequestPart("file") MultipartFile file) {
        return reconciliationService.importCsv(currentUser.id(), file);
    }

    @Operation(summary = "Executions de rapprochement", description = "Page Spring, plus recentes d abord.")
    @GetMapping("/reconciliation/runs")
    public Page<ReconciliationRunResponse> runs(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return reconciliationService.runs(page, size);
    }

    @Operation(summary = "Anomalies de rapprochement", description = "Filtrable par statut (OPEN, RESOLVED, IGNORED) ; Page Spring, plus recentes d abord.")
    @GetMapping("/reconciliation/anomalies")
    public Page<ReconciliationAnomalyResponse> anomalies(@RequestParam(required = false) AnomalyStatus status,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return reconciliationService.anomalies(status, page, size);
    }

    @Operation(summary = "Traiter une anomalie", description = "RESOLVED (corrigee, avec note) ou IGNORED (ecart accepte, avec note). Journalise.")
    @PostMapping("/reconciliation/anomalies/{id}/resolve")
    public ReconciliationAnomalyResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveAnomalyRequest req) {
        return reconciliationService.resolve(currentUser.id(), id, req);
    }

    /** « 2026-09-01 » (date civile, UTC) ou instant ISO 8601 ; null si absent. */
    static Instant parseInstant(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            LocalDate date = LocalDate.parse(v);
            return (endOfDay ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }
}
