package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.LedgerEntry;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.ReconciliationAnomaly;
import bj.ekuiseo.api.domain.ReconciliationRun;
import bj.ekuiseo.api.domain.enums.AnomalyKind;
import bj.ekuiseo.api.domain.enums.AnomalyStatus;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.ReconciliationStatus;
import bj.ekuiseo.api.domain.enums.ReconciliationTrigger;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.finance.ReconciliationAnomalyResponse;
import bj.ekuiseo.api.dto.finance.ReconciliationRunResponse;
import bj.ekuiseo.api.dto.finance.ResolveAnomalyRequest;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.ReconciliationAnomalyRepository;
import bj.ekuiseo.api.repository.ReconciliationRunRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.payment.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rapprochement Ekuiseo / fournisseur de paiement (contrat A.7, V26).
 * <ul>
 *   <li>{@link #run} : re-verifie aupres du fournisseur ({@code verifyTransaction}, avec delai
 *       borne) chaque paiement des N derniers jours en INITIATED / SUCCEEDED / REFUND_* et compare
 *       montant et statut ; verifie que chaque paiement SUCCEEDED a ses ecritures de registre
 *       equilibrees et que chaque paiement REFUNDED a son remboursement et son ecriture REFUND.
 *       Une transaction par paiement : une erreur (reseau, delai) est comptee et notee, jamais
 *       bloquante.</li>
 *   <li>{@link #importCsv} : export CSV du fournisseur (en-tetes francais ou anglais, montants
 *       « 1 000 » toleres) confronte aux paiements connus.</li>
 * </ul>
 * Les anomalies encore ouvertes ne sont pas recreees d un rapprochement a l autre.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 90;
    private static final List<PaymentStatus> CHECKED_STATUSES = List.of(PaymentStatus.INITIATED, PaymentStatus.SUCCEEDED,
            PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED, PaymentStatus.REFUND_MANUAL);
    private static final Set<PaymentStatus> COLLECTED = Set.of(PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_PENDING,
            PaymentStatus.REFUNDED, PaymentStatus.REFUND_MANUAL);
    private static final Set<String> TX_HEADERS = Set.of("transactionid", "transaction_id", "transaction", "id",
            "reference", "référence", "identifiant", "idtransaction", "id_transaction", "txid");
    private static final Set<String> AMOUNT_HEADERS = Set.of("amount", "montant", "montant_fcfa", "amountfcfa", "total");
    private static final Set<String> STATUS_HEADERS = Set.of("status", "statut", "etat", "état", "state");
    private static final Set<String> SUCCESS_WORDS = Set.of("success", "successful", "succeeded", "succes", "succès",
            "reussi", "réussi", "paid", "paye", "payé", "ok", "completed", "termine", "terminé", "valide", "validé");

    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RefundRepository refundRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationAnomalyRepository anomalyRepository;
    private final PaymentProvider paymentProvider;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final long verifyTimeoutSeconds;
    private final ExecutorService verifier = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reconciliation-verify");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ReconciliationService(PaymentRepository paymentRepository, LedgerEntryRepository ledgerEntryRepository,
                                 RefundRepository refundRepository, ReconciliationRunRepository runRepository,
                                 ReconciliationAnomalyRepository anomalyRepository, PaymentProvider paymentProvider,
                                 AuditService auditService, PlatformTransactionManager transactionManager,
                                 @Value("${ekuiseo.reconciliation.verify-timeout-seconds:20}") long verifyTimeoutSeconds) {
        this.paymentRepository = paymentRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.refundRepository = refundRepository;
        this.runRepository = runRepository;
        this.anomalyRepository = anomalyRepository;
        this.paymentProvider = paymentProvider;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.verifyTimeoutSeconds = verifyTimeoutSeconds;
    }

    /* ------------------------------------------------------------------ re-verification */

    /** Rapprochement manuel (admin) : {@code actorId} non nul. */
    public ReconciliationRunResponse run(UUID actorId, int days) {
        return run(actorId, days, ReconciliationTrigger.MANUAL);
    }

    /** Rapprochement planifie (ReconciliationScheduler). */
    public ReconciliationRunResponse runScheduled(int days) {
        return run(null, days, ReconciliationTrigger.SCHEDULED);
    }

    ReconciliationRunResponse run(UUID actorId, int days, ReconciliationTrigger trigger) {
        int period = days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        ReconciliationRun run = tx.execute(status -> runRepository.save(ReconciliationRun.builder()
                .trigger(trigger).startedBy(actorId).build()));
        Objects.requireNonNull(run);
        Instant from = Instant.now().minus(period, ChronoUnit.DAYS);
        List<UUID> paymentIds = tx.execute(status -> paymentRepository.findForReconciliation(
                        bj.ekuiseo.api.domain.enums.PaymentProvider.valueOf(paymentProvider.name()), from, CHECKED_STATUSES)
                .stream().map(Payment::getId).toList());
        int checked = 0;
        int anomalies = 0;
        int errors = 0;
        List<String> notes = new ArrayList<>();
        for (UUID paymentId : paymentIds == null ? List.<UUID>of() : paymentIds) {
            try {
                anomalies += checkPayment(run.getId(), paymentId);
                checked++;
            } catch (RuntimeException ex) {
                errors++;
                if (notes.size() < 20) {
                    notes.add("Paiement " + paymentId + " : " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                }
                log.warn("Rapprochement : paiement {} non verifie ({})", paymentId, ex.getMessage());
            }
        }
        String summary = period + " jour(s), " + checked + " paiement(s) verifie(s), " + anomalies + " ecart(s), "
                + errors + " erreur(s) de verification" + (notes.isEmpty() ? "" : "\n" + String.join("\n", notes));
        final int finalChecked = checked;
        final int finalAnomalies = anomalies;
        final int finalErrors = errors;
        ReconciliationRun finished = tx.execute(status -> {
            ReconciliationRun r = runRepository.findById(run.getId()).orElseThrow();
            r.setChecked(finalChecked);
            r.setAnomaliesFound(finalAnomalies);
            r.setFinishedAt(Instant.now());
            r.setStatus(finalErrors > 0 && finalChecked == 0 ? ReconciliationStatus.FAILED : ReconciliationStatus.DONE);
            r.setNotes(summary);
            return runRepository.save(r);
        });
        auditService.log(actorId, "RECONCILIATION_RUN", "reconciliation_run", run.getId(),
                Map.of("trigger", trigger.name(), "days", period, "checked", checked, "anomalies", anomalies, "errors", errors));
        log.info("Rapprochement {} : {}", run.getId(), summary.split("\n")[0]);
        return ReconciliationRunResponse.from(Objects.requireNonNull(finished));
    }

    /** Un paiement : verification chez le fournisseur (hors transaction) puis comparaison et anomalies (transaction courte). */
    int checkPayment(UUID runId, UUID paymentId) {
        Payment snapshot = tx.execute(status -> paymentRepository.findById(paymentId)
                .map(p -> {
                    if (p.getBooking() != null) p.getBooking().getId();
                    return p;
                }).orElse(null));
        if (snapshot == null) return 0;
        PaymentProvider.VerificationResult verified = verifyWithTimeout(snapshot.getProviderTxId());
        Integer created = tx.execute(status -> compare(runId, paymentId, verified));
        return created == null ? 0 : created;
    }

    private PaymentProvider.VerificationResult verifyWithTimeout(String transactionId) {
        CompletableFuture<PaymentProvider.VerificationResult> future =
                CompletableFuture.supplyAsync(() -> paymentProvider.verifyTransaction(transactionId), verifier);
        try {
            return future.get(verifyTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("delai de verification depasse (" + verifyTimeoutSeconds + " s)");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("verification interrompue", ex);
        }
    }

    /** Compare le paiement a la reponse du fournisseur et au registre ; renvoie le nombre d anomalies creees. */
    int compare(UUID runId, UUID paymentId, PaymentProvider.VerificationResult verified) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) return 0;
        int created = 0;
        boolean collected = COLLECTED.contains(payment.getStatus());
        long ourAmount = payment.getVerifiedAmount() != null && payment.getVerifiedAmount() > 0
                ? payment.getVerifiedAmount() : payment.getAmount();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("status", payment.getStatus().name());
        expected.put("amountFcfa", ourAmount);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("success", verified.success());
        observed.put("rawStatus", verified.rawStatus());
        observed.put("amountFcfa", verified.amountFcfa());
        observed.put("failureCode", verified.failureCode());

        if (collected && !verified.success()) {
            if (verified.unknownTransaction()) {
                created += open(runId, payment, AnomalyKind.MISSING_AT_PROVIDER, expected, observed);
            } else if (isConclusiveFailure(verified)) {
                created += open(runId, payment, AnomalyKind.STATUS_MISMATCH, expected, observed);
            }
            // reponse non conclusive (transitoire) : pas d anomalie
        } else if (payment.getStatus() == PaymentStatus.INITIATED && verified.success()) {
            created += open(runId, payment, AnomalyKind.STATUS_MISMATCH, expected, observed);
        } else if (collected && verified.success() && verified.amountFcfa() > 0 && verified.amountFcfa() != ourAmount) {
            created += open(runId, payment, AnomalyKind.AMOUNT_MISMATCH, expected, observed);
        }
        if (collected) {
            created += checkLedger(runId, payment, ourAmount);
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            created += checkRefund(runId, payment);
        }
        return created;
    }

    private static boolean isConclusiveFailure(PaymentProvider.VerificationResult verified) {
        String raw = verified.rawStatus() == null ? "" : verified.rawStatus().toUpperCase(Locale.ROOT);
        if (raw.isEmpty() || raw.equals("EMPTY_RESPONSE") || raw.startsWith("HTTP_")) return false;
        return !(raw.contains("PENDING") || raw.contains("PROCESSING") || raw.contains("INITIATED") || raw.contains("WAITING"));
    }

    /** PASSENGER_PAYMENT = PLATFORM_COMMISSION + DRIVER_SHARE, et PASSENGER_PAYMENT = montant encaisse. */
    private int checkLedger(UUID runId, Payment payment, long collectedAmount) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());
        long paid = sum(entries, LedgerEntryType.PASSENGER_PAYMENT);
        long commission = sum(entries, LedgerEntryType.PLATFORM_COMMISSION);
        long driverShare = sum(entries, LedgerEntryType.DRIVER_SHARE);
        boolean legacy = entries.isEmpty() && (payment.getUpdatedAt() == null
                || payment.getUpdatedAt().isBefore(Instant.parse("2026-09-10T00:00:00Z")));
        if (legacy) {
            return 0; // paiement anterieur au registre : rien a exiger
        }
        if (paid != collectedAmount || paid != commission + driverShare) {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("passengerPaymentFcfa", collectedAmount);
            expected.put("rule", "PASSENGER_PAYMENT = PLATFORM_COMMISSION + DRIVER_SHARE");
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("passengerPaymentFcfa", paid);
            observed.put("platformCommissionFcfa", commission);
            observed.put("driverShareFcfa", driverShare);
            observed.put("entries", entries.size());
            return open(runId, payment, AnomalyKind.LEDGER_IMBALANCE, expected, observed);
        }
        return 0;
    }

    /** Un paiement REFUNDED doit avoir un remboursement SUCCEEDED et son ecriture REFUND. */
    private int checkRefund(UUID runId, Payment payment) {
        Optional<bj.ekuiseo.api.domain.Refund> succeeded = refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId())
                .stream().filter(r -> r.getStatus() == RefundStatus.SUCCEEDED).findFirst();
        boolean ledgerOk = succeeded.isPresent()
                && ledgerEntryRepository.existsByRefundIdAndEntryType(succeeded.get().getId(), LedgerEntryType.REFUND);
        boolean legacy = succeeded.isPresent() && succeeded.get().getCompletedAt() != null
                && succeeded.get().getCompletedAt().isBefore(Instant.parse("2026-09-10T00:00:00Z"));
        if (succeeded.isEmpty() || (!ledgerOk && !legacy)) {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("refundStatus", "SUCCEEDED");
            expected.put("ledgerEntry", "REFUND");
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("refundFound", succeeded.isPresent());
            observed.put("ledgerEntryFound", ledgerOk);
            return open(runId, payment, AnomalyKind.REFUND_MISSING, expected, observed);
        }
        return 0;
    }

    private static long sum(List<LedgerEntry> entries, LedgerEntryType type) {
        return entries.stream().filter(e -> e.getEntryType() == type).mapToLong(LedgerEntry::getAmountFcfa).sum();
    }

    private int open(UUID runId, Payment payment, AnomalyKind kind, Map<String, Object> expected, Map<String, Object> observed) {
        if (anomalyRepository.existsByKindAndPaymentIdAndStatus(kind, payment.getId(), AnomalyStatus.OPEN)) {
            return 0;
        }
        anomalyRepository.save(ReconciliationAnomaly.builder()
                .runId(runId).kind(kind).paymentId(payment.getId())
                .bookingId(payment.getBooking() != null ? payment.getBooking().getId() : null)
                .providerTxId(payment.getProviderTxId()).expected(expected).observed(observed).build());
        log.warn("Rapprochement : ecart {} sur le paiement {} ({})", kind, payment.getId(), payment.getProviderTxId());
        return 1;
    }

    private int openForTx(UUID runId, AnomalyKind kind, String providerTxId, Map<String, Object> expected, Map<String, Object> observed) {
        if (anomalyRepository.existsByKindAndProviderTxIdAndStatus(kind, providerTxId, AnomalyStatus.OPEN)) {
            return 0;
        }
        anomalyRepository.save(ReconciliationAnomaly.builder()
                .runId(runId).kind(kind).providerTxId(providerTxId).expected(expected).observed(observed).build());
        return 1;
    }

    /* ------------------------------------------------------------------ import CSV */

    /** Ligne utile de l export du fournisseur. */
    record ProviderRow(String transactionId, Long amountFcfa, String status, int line) {
    }

    @Transactional
    public ReconciliationRunResponse importCsv(UUID actorId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fichier CSV absent ou vide");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BadRequestException("Fichier CSV illisible");
        }
        List<ProviderRow> rows = parseCsv(content);
        ReconciliationRun run = runRepository.save(ReconciliationRun.builder()
                .trigger(ReconciliationTrigger.IMPORT).startedBy(actorId).build());
        int anomalies = 0;
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();
        for (ProviderRow row : rows) {
            if (!seen.add(row.transactionId())) {
                if (duplicated.add(row.transactionId())) {
                    Map<String, Object> observed = new LinkedHashMap<>();
                    observed.put("line", row.line());
                    observed.put("amountFcfa", row.amountFcfa());
                    anomalies += openForTx(run.getId(), AnomalyKind.DUPLICATE_PROVIDER_TX, row.transactionId(),
                            Map.of("occurrences", 1), observed);
                }
                continue;
            }
            Optional<Payment> payment = paymentRepository.findByProviderAndProviderTxId(
                    bj.ekuiseo.api.domain.enums.PaymentProvider.valueOf(paymentProvider.name()), row.transactionId());
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("line", row.line());
            observed.put("amountFcfa", row.amountFcfa());
            observed.put("status", row.status());
            if (payment.isEmpty()) {
                anomalies += openForTx(run.getId(), AnomalyKind.UNKNOWN_AT_PROVIDER, row.transactionId(),
                        Map.of("knownByEkuiseo", false), observed);
                continue;
            }
            Payment p = payment.get();
            long ourAmount = p.getVerifiedAmount() != null && p.getVerifiedAmount() > 0 ? p.getVerifiedAmount() : p.getAmount();
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("status", p.getStatus().name());
            expected.put("amountFcfa", ourAmount);
            boolean providerSuccess = isSuccessWord(row.status());
            boolean collected = COLLECTED.contains(p.getStatus());
            if (row.status() != null && !row.status().isBlank() && providerSuccess != collected) {
                anomalies += open(run.getId(), p, AnomalyKind.STATUS_MISMATCH, expected, observed);
            } else if (row.amountFcfa() != null && collected && row.amountFcfa() != ourAmount) {
                anomalies += open(run.getId(), p, AnomalyKind.AMOUNT_MISMATCH, expected, observed);
            }
        }
        run.setChecked(rows.size());
        run.setAnomaliesFound(anomalies);
        run.setFinishedAt(Instant.now());
        run.setStatus(ReconciliationStatus.DONE);
        run.setNotes("Import « " + sanitize(file.getOriginalFilename()) + "» : " + rows.size() + " ligne(s), " + anomalies + " ecart(s)");
        run = runRepository.save(run);
        auditService.log(actorId, "RECONCILIATION_IMPORT", "reconciliation_run", run.getId(),
                Map.of("rows", rows.size(), "anomalies", anomalies, "file", sanitize(file.getOriginalFilename())));
        return ReconciliationRunResponse.from(run);
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[\\r\\n\";]", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /**
     * Analyse tolerante : separateur ',' ou ';' detecte sur l en-tete, colonnes reconnues par nom
     * (francais ou anglais, casse et accents ignores), guillemets simples geres, montants « 1 000 »,
     * « 1 000 » (espace insecable) ou « 1000,00 » acceptes. Une ligne sans identifiant est ignoree.
     */
    static List<ProviderRow> parseCsv(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        String[] lines = text.split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new BadRequestException("Fichier CSV vide");
        }
        char separator = lines[0].chars().filter(c -> c == ';').count() >= lines[0].chars().filter(c -> c == ',').count() ? ';' : ',';
        List<String> headers = split(lines[0], separator).stream().map(ReconciliationService::normalizeHeader).toList();
        int txIdx = indexOf(headers, TX_HEADERS);
        int amountIdx = indexOf(headers, AMOUNT_HEADERS);
        int statusIdx = indexOf(headers, STATUS_HEADERS);
        if (txIdx < 0) {
            throw new BadRequestException("Colonne d identifiant de transaction introuvable (transactionId, reference...)");
        }
        List<ProviderRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = split(lines[i], separator);
            String txId = cell(cells, txIdx);
            if (txId.isEmpty()) continue;
            rows.add(new ProviderRow(txId, parseAmount(cell(cells, amountIdx)), cell(cells, statusIdx), i + 1));
        }
        return rows;
    }

    private static String normalizeHeader(String header) {
        String h = header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replace("\"", "");
        return java.text.Normalizer.normalize(h, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace(" ", "");
    }

    private static int indexOf(List<String> headers, Set<String> candidates) {
        Set<String> normalized = new HashSet<>();
        for (String c : candidates) normalized.add(normalizeHeader(c));
        for (int i = 0; i < headers.size(); i++) {
            if (normalized.contains(headers.get(i))) return i;
        }
        return -1;
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) return "";
        return cells.get(index).trim();
    }

    static List<String> split(String line, char separator) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == separator && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    /** « 1 000 », « 1 000 », « 1000,00 », « 1,000.00 », « 1000 FCFA » -> 1000 ; null si vide ou illisible. */
    static Long parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace(" ", "").replace(" ", "").replace(" ", "")
                .replaceAll("(?i)f?cfa|xof", "").trim();
        // Decimales : virgule ou point suivi de 1 ou 2 chiffres en fin de chaine.
        s = s.replaceAll("[.,]\\d{1,2}$", "");
        s = s.replace(",", "").replace(".", "");
        if (s.isEmpty() || !s.matches("-?\\d+")) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean isSuccessWord(String status) {
        if (status == null) return false;
        String s = normalizeHeader(status);
        return SUCCESS_WORDS.stream().map(ReconciliationService::normalizeHeader).anyMatch(s::equals);
    }

    /* ------------------------------------------------------------------ lectures et decisions */

    @Transactional(readOnly = true)
    public Page<ReconciliationRunResponse> runs(int page, int size) {
        return runRepository.findAllByOrderByStartedAtDesc(Paging.of(page, size)).map(ReconciliationRunResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationAnomalyResponse> anomalies(AnomalyStatus status, int page, int size) {
        Page<ReconciliationAnomaly> result = status == null
                ? anomalyRepository.findAllByOrderByCreatedAtDesc(Paging.of(page, size))
                : anomalyRepository.findByStatusOrderByCreatedAtDesc(status, Paging.of(page, size));
        return result.map(ReconciliationAnomalyResponse::from);
    }

    @Transactional
    public ReconciliationAnomalyResponse resolve(UUID adminId, UUID anomalyId, ResolveAnomalyRequest req) {
        if (req.status() != AnomalyStatus.RESOLVED && req.status() != AnomalyStatus.IGNORED) {
            throw new BadRequestException("Le statut doit etre RESOLVED ou IGNORED");
        }
        ReconciliationAnomaly anomaly = anomalyRepository.findById(anomalyId)
                .orElseThrow(() -> new NotFoundException("Anomalie introuvable"));
        if (anomaly.getStatus() != AnomalyStatus.OPEN) {
            throw new ConflictException("Cette anomalie est deja " + anomaly.getStatus());
        }
        anomaly.setStatus(req.status());
        anomaly.setResolutionNote(req.note().trim());
        anomaly.setResolvedBy(adminId);
        anomaly.setResolvedAt(Instant.now());
        anomaly = anomalyRepository.save(anomaly);
        Map<String, Object> details = new HashMap<>();
        details.put("status", req.status().name());
        details.put("kind", anomaly.getKind().name());
        details.put("paymentId", Objects.toString(anomaly.getPaymentId(), ""));
        details.put("note", req.note().trim());
        auditService.log(adminId, "RECONCILIATION_ANOMALY_RESOLVED", "reconciliation_anomaly", anomaly.getId(), details);
        return ReconciliationAnomalyResponse.from(anomaly);
    }
}
