package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentEventSource;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.RefundKind;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.payment.AdminRefundResponse;
import bj.ekuiseo.api.dto.payment.RefundResponse;
import bj.ekuiseo.api.dto.payment.RefundSummaryResponse;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.payment.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Remboursements en deux temps (constats F004, F036, F105, F106 de l audit), refondus autour de
 * l entite {@link Refund} et de sa machine d etat (contrat A.3, V26) :
 *
 * <ol>
 *   <li><b>Decision</b>, dans la transaction metier (annulation, paiement orphelin, echeance
 *       d un litige) : une ligne {@code refunds} est creee REQUESTED (ou MANUAL_REVIEW pour un
 *       montant partiel ou un paiement sans reference exploitable), le paiement passe
 *       REFUND_PENDING / REFUND_MANUAL et ses colonnes refund_* restent renseignees (front et KPI
 *       existants), la reservation est retiree du lot de reversement, le passager est prevenu.
 *       Rien ne sort vers l agregateur. Le paiement est verrouille (SELECT FOR UPDATE) pendant
 *       la decision : deux annulations simultanees ne creent jamais deux remboursements, et
 *       l index unique partiel {@code uq_refunds_live_payment} reste la garde finale.</li>
 *   <li><b>Execution</b>, apres validation de la transaction (afterCommit) puis par reprise
 *       planifiee : REQUESTED / FAILED -> PROCESSING sous verrou, appel du fournisseur HORS
 *       transaction, puis SUCCEEDED (paiement REFUNDED, ecritures du registre, passager prevenu)
 *       ou FAILED (reprise plus tard) ou MANUAL_REVIEW au-dela de
 *       {@code ekuiseo.refund.max-attempts} tentatives (file du back-office).</li>
 * </ol>
 * Chaque transition ecrit un {@link PaymentEventService evenement de paiement}.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    public static final String REASON_ORPHAN = "PAYMENT_ORPHAN";
    public static final String REASON_AMOUNT_INSUFFICIENT = "AMOUNT_INSUFFICIENT";
    /** Une execution restee PROCESSING au-dela de ce delai est reprise (arret pendant l appel au fournisseur). */
    static final long STALE_PROCESSING_MINUTES = 10;
    private static final List<RefundStatus> OPEN_STATUSES =
            List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.FAILED, RefundStatus.MANUAL_REVIEW);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProvider paymentProvider;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final PayoutService payoutService;
    private final LedgerService ledgerService;
    private final PaymentEventService paymentEventService;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final int maxAttempts;

    public RefundService(PaymentRepository paymentRepository, RefundRepository refundRepository,
                         PaymentProvider paymentProvider, AuditService auditService,
                         NotificationService notificationService, PayoutService payoutService,
                         LedgerService ledgerService, PaymentEventService paymentEventService,
                         PlatformTransactionManager transactionManager,
                         @Qualifier("refundExecutor") Executor executor,
                         @Value("${ekuiseo.refund.max-attempts:5}") int maxAttempts) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentProvider = paymentProvider;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.payoutService = payoutService;
        this.ledgerService = ledgerService;
        this.paymentEventService = paymentEventService;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.maxAttempts = maxAttempts;
    }

    public enum RequestStatus { REQUESTED, MANUAL_REQUIRED, NOT_APPLICABLE, NO_PAYMENT, ALREADY_REQUESTED }

    public record RequestOutcome(RequestStatus status, String message, UUID refundId) {
        public RequestOutcome(RequestStatus status, String message) {
            this(status, message, null);
        }
    }

    /* ------------------------------------------------------------------ decision */

    /**
     * Annulation d une reservation : marque le paiement encaisse a rembourser et ajuste
     * les lots de reversement. A appeler DANS la transaction d annulation.
     */
    @Transactional
    public RequestOutcome requestForBooking(Booking booking, long refundAmountFcfa, String reason) {
        payoutService.excludeCancelledBooking(booking, reason);
        if (booking.getPaymentMethod() == PaymentMethod.CASH) {
            return new RequestOutcome(RequestStatus.NOT_APPLICABLE, "Paiement especes : aucun remboursement electronique necessaire");
        }
        if (refundAmountFcfa <= 0) {
            return new RequestOutcome(RequestStatus.NOT_APPLICABLE, "Aucun montant a rembourser");
        }
        Optional<Payment> succeeded = paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(
                booking.getId(), PaymentStatus.SUCCEEDED);
        if (succeeded.isEmpty()) {
            if (paymentRepository.findByBookingId(booking.getId()).stream().anyMatch(p -> isRefundState(p.getStatus()))) {
                return new RequestOutcome(RequestStatus.ALREADY_REQUESTED, "Un remboursement est deja en cours pour cette reservation");
            }
            log.warn("Remboursement demande pour la reservation {} mais aucun paiement SUCCEEDED trouve", booking.getId());
            auditService.log(null, "REFUND_NO_PAYMENT_FOUND", "booking", booking.getId(), Map.of("reason", reason));
            return new RequestOutcome(RequestStatus.NO_PAYMENT, "Aucun paiement reussi trouve pour cette reservation");
        }
        // Verrou sur le paiement : une decision concurrente (annulation croisee, echeance) attend
        // ici puis retrouve un paiement deja en remboursement.
        Payment payment = paymentRepository.findByIdForUpdate(succeeded.get().getId()).orElse(succeeded.get());
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            return new RequestOutcome(RequestStatus.ALREADY_REQUESTED, "Un remboursement est deja en cours pour ce paiement");
        }
        return mark(payment, booking.getPassenger(), Math.min(refundAmountFcfa, payment.getAmount()), payment.getAmount(),
                reason, null);
    }

    /**
     * Paiement verifie SUCCEEDED alors que la reservation n est plus en attente (expiree,
     * annulee) ou montant insuffisant : l argent encaisse repart integralement. A appeler
     * dans la transaction qui vient de poser SUCCEEDED sur le paiement (deja verrouille).
     */
    @Transactional
    public RequestOutcome requestForOrphanPayment(Payment payment, User passenger, String reason, long paidAmountFcfa) {
        if (paidAmountFcfa <= 0) {
            return new RequestOutcome(RequestStatus.NOT_APPLICABLE, "Aucun montant encaisse");
        }
        // On rembourse tout ce que le fournisseur a reellement encaisse (montant verifie), meme
        // s il etait inferieur a l attendu : le remboursement porte sur la transaction entiere.
        return mark(payment, passenger, paidAmountFcfa, paidAmountFcfa, reason, null);
    }

    static boolean isRefundState(PaymentStatus status) {
        return status == PaymentStatus.REFUND_PENDING || status == PaymentStatus.REFUND_MANUAL || status == PaymentStatus.REFUNDED;
    }

    private RequestOutcome mark(Payment payment, User passenger, long amount, long paidAmount, String reason, UUID requestedBy) {
        if (payment.getId() != null && !refundRepository.findLiveByPaymentId(payment.getId()).isEmpty()) {
            return new RequestOutcome(RequestStatus.ALREADY_REQUESTED, "Un remboursement est deja en cours pour ce paiement");
        }
        PaymentStatus previous = payment.getStatus();
        boolean partial = amount < paidAmount;
        boolean noGatewayId = payment.getProviderTxId() == null || payment.getProviderTxId().startsWith("ekuiseo-");
        UUID bookingId = payment.getBooking() != null ? payment.getBooking().getId() : null;
        Instant now = Instant.now();
        // Colonnes historiques de payments conservees en miroir (front, KPI, export des donnees).
        payment.setRefundAmount(amount);
        payment.setRefundReason(reason);
        payment.setRefundRequestedAt(now);
        payment.setRefundAttempts(0);
        Refund.RefundBuilder builder = Refund.builder()
                .payment(payment).bookingId(bookingId).amountFcfa(amount)
                .kind(partial ? RefundKind.PARTIAL : RefundKind.FULL).reason(reason).requestedBy(requestedBy);
        if (partial || noGatewayId) {
            String cause = partial
                    ? "Remboursement partiel (" + amount + "/" + paidAmount + " FCFA) : l API du fournisseur ne rembourse que le montant total"
                    : "Aucun identifiant de transaction du fournisseur sur ce paiement";
            payment.setStatus(PaymentStatus.REFUND_MANUAL);
            payment.setRefundLastError(cause);
            paymentRepository.save(payment);
            Refund refund = refundRepository.save(builder.status(RefundStatus.MANUAL_REVIEW).lastError(cause).build());
            paymentEventService.record(payment, PaymentEventService.REFUND_MANUAL_REVIEW, previous, PaymentStatus.REFUND_MANUAL,
                    requestedBy == null ? PaymentEventSource.SYSTEM : PaymentEventSource.ADMIN, requestedBy,
                    PaymentEventService.details("refundId", refund.getId().toString(), "amountFcfa", amount, "reason", reason,
                            "cause", partial ? "partial" : "no-gateway-id"));
            auditService.log(requestedBy, "REFUND_MANUAL_REQUIRED", "payment", payment.getId(),
                    Map.of("reason", reason, "refundAmountFcfa", amount, "paidFcfa", paidAmount,
                            "bookingId", String.valueOf(bookingId), "cause", partial ? "partial" : "no-gateway-id",
                            "refundId", refund.getId().toString()));
            notifyPending(passenger, payment, amount, true);
            return new RequestOutcome(RequestStatus.MANUAL_REQUIRED,
                    "Remboursement de " + amount + " FCFA a traiter manuellement par le back-office", refund.getId());
        }
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setRefundLastError(null);
        paymentRepository.save(payment);
        Refund refund = refundRepository.save(builder.status(RefundStatus.REQUESTED).build());
        paymentEventService.record(payment, PaymentEventService.REFUND_REQUESTED, previous, PaymentStatus.REFUND_PENDING,
                requestedBy == null ? PaymentEventSource.SYSTEM : PaymentEventSource.ADMIN, requestedBy,
                PaymentEventService.details("refundId", refund.getId().toString(), "amountFcfa", amount, "reason", reason));
        auditService.log(requestedBy, "PAYMENT_REFUND_REQUESTED", "payment", payment.getId(),
                Map.of("reason", reason, "refundAmountFcfa", amount, "bookingId", String.valueOf(bookingId),
                        "refundId", refund.getId().toString()));
        notifyPending(passenger, payment, amount, false);
        scheduleAfterCommit(refund.getId());
        return new RequestOutcome(RequestStatus.REQUESTED, "Remboursement de " + amount + " FCFA demande a l agregateur", refund.getId());
    }

    private void notifyPending(User passenger, Payment payment, long amount, boolean manual) {
        if (passenger == null) return;
        try {
            notificationService.notify(passenger, NotificationType.PAYMENT_REFUND_PENDING, Map.of(
                    "paymentId", payment.getId().toString(),
                    "bookingId", payment.getBooking() != null ? payment.getBooking().getId().toString() : "",
                    "amountFcfa", amount,
                    "manual", manual));
        } catch (RuntimeException ex) {
            log.warn("Notification de remboursement non enregistree pour le paiement {}", payment.getId(), ex);
        }
    }

    /** L execution part une fois la transaction metier validee ; sans transaction (tests, admin), tout de suite. */
    private void scheduleAfterCommit(UUID refundId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> process(refundId));
                }
            });
        } else {
            executor.execute(() -> process(refundId));
        }
    }

    /* ------------------------------------------------------------------ execution */

    /**
     * Execute un remboursement : REQUESTED / FAILED (reprise) -> PROCESSING sous verrou, appel du
     * fournisseur HORS transaction, puis ecriture courte du resultat. Idempotent : ne fait rien
     * si le remboursement n est pas (ou plus) executable ; deux executions concurrentes ne
     * partent jamais toutes les deux (la seconde retrouve PROCESSING et s arrete).
     */
    public void process(UUID refundId) {
        String txId = tx.execute(status -> refundRepository.findByIdForUpdate(refundId)
                .filter(r -> r.getStatus() == RefundStatus.REQUESTED
                        || (r.getStatus() == RefundStatus.FAILED && r.getCompletedAt() == null && r.getAttempts() < maxAttempts)
                        || (r.getStatus() == RefundStatus.PROCESSING && r.getUpdatedAt() != null
                            && r.getUpdatedAt().isBefore(Instant.now().minusSeconds(STALE_PROCESSING_MINUTES * 60))))
                .map(refund -> {
                    Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId()).orElse(refund.getPayment());
                    RefundStatus previous = refund.getStatus();
                    refund.setStatus(RefundStatus.PROCESSING);
                    refund.setUpdatedAt(Instant.now());
                    refundRepository.save(refund);
                    paymentEventService.record(payment, PaymentEventService.REFUND_PROCESSING, payment.getStatus(), payment.getStatus(),
                            PaymentEventSource.SCHEDULER, null,
                            PaymentEventService.details("refundId", refund.getId().toString(), "from", previous.name(),
                                    "attempt", refund.getAttempts() + 1));
                    return payment.getProviderTxId();
                })
                .orElse(null));
        if (txId == null) {
            return;
        }
        PaymentProvider.RefundResult result;
        try {
            result = paymentProvider.refundTransaction(txId);
        } catch (RuntimeException ex) {
            result = new PaymentProvider.RefundResult(false, "EXCEPTION", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        final PaymentProvider.RefundResult outcome = result;
        tx.executeWithoutResult(status -> refundRepository.findByIdForUpdate(refundId)
                .filter(r -> r.getStatus() == RefundStatus.PROCESSING)
                .ifPresent(refund -> record(refund, outcome)));
    }

    private void record(Refund refund, PaymentProvider.RefundResult result) {
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId()).orElse(refund.getPayment());
        if (result.success()) {
            succeed(refund, payment, result.rawStatus(), PaymentEventSource.SCHEDULER, null, "PAYMENT_REFUNDED",
                    Map.of("gatewayMessage", String.valueOf(result.message())));
            log.info("Remboursement {} confirme par le fournisseur pour le paiement {} ({} FCFA)",
                    refund.getId(), payment.getId(), refund.getAmountFcfa());
            return;
        }
        int attempts = refund.getAttempts() + 1;
        String error = String.valueOf(result.message());
        String truncated = error.length() > 500 ? error.substring(0, 500) : error;
        refund.setAttempts(attempts);
        refund.setLastError(truncated);
        payment.setRefundAttempts(attempts);
        payment.setRefundLastError(truncated);
        UUID bookingId = refund.getBookingId();
        if (attempts >= maxAttempts) {
            refund.setStatus(RefundStatus.MANUAL_REVIEW);
            payment.setStatus(PaymentStatus.REFUND_MANUAL);
            paymentEventService.record(payment, PaymentEventService.REFUND_MANUAL_REVIEW, PaymentStatus.REFUND_PENDING,
                    PaymentStatus.REFUND_MANUAL, PaymentEventSource.SCHEDULER, null,
                    PaymentEventService.details("refundId", refund.getId().toString(), "attempts", attempts, "lastError", truncated));
            auditService.log(null, "REFUND_MANUAL_REQUIRED", "payment", payment.getId(),
                    Map.of("cause", "gateway-failed", "attempts", attempts, "lastError", truncated,
                            "bookingId", String.valueOf(bookingId), "refundId", refund.getId().toString()));
            log.error("Remboursement {} abandonne apres {} tentatives : {}", refund.getId(), attempts, truncated);
        } else {
            refund.setStatus(RefundStatus.FAILED);
            paymentEventService.record(payment, PaymentEventService.REFUND_FAILED, payment.getStatus(), payment.getStatus(),
                    PaymentEventSource.SCHEDULER, null,
                    PaymentEventService.details("refundId", refund.getId().toString(), "attempt", attempts, "lastError", truncated));
            log.warn("Remboursement {} echoue (tentative {}/{}) : {}", refund.getId(), attempts, maxAttempts, truncated);
        }
        refund.setUpdatedAt(Instant.now());
        refundRepository.save(refund);
        paymentRepository.save(payment);
    }

    /** SUCCEEDED : paiement REFUNDED, registre, audit, passager prevenu. Commun a l execution et au marquage admin. */
    private void succeed(Refund refund, Payment payment, String providerReference, PaymentEventSource source, UUID actorId,
                         String auditAction, Map<String, Object> extraAudit) {
        Instant now = Instant.now();
        PaymentStatus previous = payment.getStatus();
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setCompletedAt(now);
        refund.setLastError(null);
        if (providerReference != null && !providerReference.isBlank()) {
            refund.setProviderReference(providerReference.trim().length() > 100 ? providerReference.trim().substring(0, 100) : providerReference.trim());
        }
        refund.setUpdatedAt(now);
        refundRepository.save(refund);
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(now);
        payment.setRefundLastError(null);
        paymentRepository.save(payment);
        ledgerService.recordRefundSucceeded(refund);
        paymentEventService.record(payment, source == PaymentEventSource.ADMIN ? PaymentEventService.REFUND_MARKED_SUCCEEDED
                        : PaymentEventService.REFUND_SUCCEEDED, previous, PaymentStatus.REFUNDED, source, actorId,
                PaymentEventService.details("refundId", refund.getId().toString(), "amountFcfa", refund.getAmountFcfa(),
                        "providerReference", refund.getProviderReference()));
        Map<String, Object> audit = new java.util.LinkedHashMap<>();
        audit.put("amountFcfa", refund.getAmountFcfa());
        audit.put("bookingId", String.valueOf(refund.getBookingId()));
        audit.put("refundId", refund.getId().toString());
        audit.putAll(extraAudit);
        auditService.log(actorId, auditAction, "payment", payment.getId(), audit);
        User passenger = payment.getBooking() != null ? payment.getBooking().getPassenger()
                : payment.getSubscription() != null ? payment.getSubscription().getDriver() : null;
        if (passenger != null) {
            notificationService.notify(passenger, NotificationType.PAYMENT_REFUNDED, Map.of(
                    "paymentId", payment.getId().toString(), "bookingId", String.valueOf(refund.getBookingId()),
                    "amountFcfa", refund.getAmountFcfa()));
        }
    }

    /** Reprise planifiee : demandes jamais executees, echecs transitoires, executions restees en cours. */
    public int retryPending(Instant before) {
        Instant stale = Instant.now().minusSeconds(STALE_PROCESSING_MINUTES * 60);
        List<UUID> ids = tx.execute(status -> refundRepository.findRetryable(before, stale, maxAttempts));
        if (ids == null) return 0;
        ids.forEach(this::process);
        return ids.size();
    }

    /* ------------------------------------------------------------------ lectures */

    /**
     * Sort de l argent encaisse sur une reservation, pour le passager (V25) : le dernier remboursement
     * demande, en cours, a traiter a la main ou effectue ; vide si rien n a ete decide ou si le seul
     * remboursement a ete abandonne definitivement.
     */
    @Transactional(readOnly = true)
    public Optional<RefundSummaryResponse> summaryForBooking(UUID bookingId) {
        return refundRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED || r.getCompletedAt() == null)
                .max(Comparator.comparing(Refund::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(r -> new RefundSummaryResponse(
                        r.getStatus() == RefundStatus.SUCCEEDED ? RefundSummaryResponse.REFUNDED
                                : r.getStatus() == RefundStatus.MANUAL_REVIEW ? RefundSummaryResponse.MANUAL : RefundSummaryResponse.PENDING,
                        r.getAmountFcfa(), r.getCreatedAt(), r.getCompletedAt()));
    }

    /** Dernier remboursement vivant ou abouti d une reservation, forme complete (contrat A.2/A.3). */
    @Transactional(readOnly = true)
    public Optional<RefundResponse> latestForBooking(UUID bookingId) {
        return refundRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED || r.getCompletedAt() == null)
                .findFirst().map(RefundResponse::from);
    }

    @Transactional(readOnly = true)
    public List<AdminRefundResponse> listForBookings(List<UUID> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) return List.of();
        return refundRepository.findByBookingIdInOrderByCreatedAtDesc(bookingIds).stream().map(AdminRefundResponse::from).toList();
    }

    /* ------------------------------------------------------------------ back-office */

    /** GET /api/v1/admin/refunds?status= : plus recents d abord ; sans filtre, tous les statuts. */
    @Transactional(readOnly = true)
    public Page<AdminRefundResponse> listRefundsForAdmin(RefundStatus status, int page, int size) {
        List<RefundStatus> statuses = status == null ? List.of(RefundStatus.values()) : List.of(status);
        return refundRepository.findForAdmin(statuses, Paging.of(page, size, Sort.by("createdAt").descending()))
                .map(AdminRefundResponse::from);
    }

    /**
     * Vue historique GET /api/v1/admin/payments (AdminPaymentResponse), desormais alimentee depuis
     * {@code refunds} : REFUND_PENDING = remboursements en attente ou en reprise, REFUND_MANUAL =
     * en examen, REFUNDED = aboutis, ALL = tous ; par defaut la file de travail (les deux premiers).
     */
    @Transactional(readOnly = true)
    public List<AdminPaymentResponse> listForAdmin(String statusFilter) {
        List<RefundStatus> statuses = switch (statusFilter == null ? "" : statusFilter.toUpperCase()) {
            case "REFUND_PENDING" -> List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.FAILED);
            case "REFUND_MANUAL" -> List.of(RefundStatus.MANUAL_REVIEW);
            case "REFUNDED" -> List.of(RefundStatus.SUCCEEDED);
            case "ALL" -> List.of(RefundStatus.values());
            default -> List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.FAILED, RefundStatus.MANUAL_REVIEW);
        };
        return refundRepository.findForAdmin(statuses, Paging.of(0, Paging.MAX_PAGE_SIZE, Sort.by("createdAt").descending()))
                .getContent().stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED || r.getCompletedAt() == null || "ALL".equalsIgnoreCase(statusFilter))
                .map(RefundService::toAdminPayment).toList();
    }

    /** Relance immediate (synchrone) : l administrateur voit le resultat. */
    public AdminRefundResponse retry(UUID adminId, UUID refundId) {
        tx.executeWithoutResult(status -> {
            Refund refund = refundRepository.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new NotFoundException("Remboursement introuvable"));
            if (refund.getStatus() == RefundStatus.SUCCEEDED) {
                throw new ConflictException("Ce remboursement est deja effectue");
            }
            if (refund.getStatus() == RefundStatus.PROCESSING) {
                throw new ConflictException("Ce remboursement est en cours d execution");
            }
            Payment payment = refund.getPayment();
            if (payment.getProviderTxId() == null || payment.getProviderTxId().startsWith("ekuiseo-")) {
                throw new BadRequestException("Aucun identifiant du fournisseur sur ce paiement : remboursez depuis son tableau de bord puis marquez-le rembourse");
            }
            if (refund.getKind() == RefundKind.PARTIAL) {
                throw new BadRequestException("Montant partiel : remboursez depuis le tableau de bord du fournisseur puis marquez-le rembourse");
            }
            RefundStatus previous = refund.getStatus();
            refund.setStatus(RefundStatus.REQUESTED);
            refund.setAttempts(0);
            refund.setCompletedAt(null);
            refund.setUpdatedAt(Instant.now());
            refundRepository.save(refund);
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setRefundAttempts(0);
            paymentRepository.save(payment);
            paymentEventService.record(payment, PaymentEventService.REFUND_RETRIED, previous == RefundStatus.MANUAL_REVIEW
                            ? PaymentStatus.REFUND_MANUAL : PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_PENDING,
                    PaymentEventSource.ADMIN, adminId, PaymentEventService.details("refundId", refund.getId().toString(), "from", previous.name()));
            auditService.log(adminId, "PAYMENT_REFUND_RETRIED", "payment", payment.getId(),
                    Map.of("refundId", refund.getId().toString(), "previousStatus", previous.name()));
        });
        process(refundId);
        return tx.execute(status -> AdminRefundResponse.from(refundRepository.findById(refundId).orElseThrow()));
    }

    /** L admin a rembourse a la main (tableau de bord du fournisseur, virement) : SUCCEEDED avec sa reference. */
    @Transactional
    public AdminRefundResponse markSucceeded(UUID adminId, UUID refundId, String providerReference) {
        Refund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new NotFoundException("Remboursement introuvable"));
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            throw new ConflictException("Ce remboursement est deja effectue");
        }
        if (refund.getStatus() == RefundStatus.FAILED && refund.getCompletedAt() != null) {
            throw new ConflictException("Ce remboursement a ete abandonne definitivement");
        }
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId()).orElse(refund.getPayment());
        succeed(refund, payment, providerReference, PaymentEventSource.ADMIN, adminId, "PAYMENT_MARKED_REFUNDED",
                Map.of("note", providerReference == null ? "" : providerReference));
        return AdminRefundResponse.from(refund);
    }

    /**
     * Abandon definitif par l administration (POST /admin/refunds/{id}/fail) : le remboursement
     * passe FAILED avec sa date de cloture, le paiement redevient SUCCEEDED (l argent reste
     * encaisse). Decision exceptionnelle, motif obligatoire et journalise ; a expliquer au
     * passager par ailleurs.
     */
    @Transactional
    public AdminRefundResponse fail(UUID adminId, UUID refundId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Le motif est obligatoire");
        }
        Refund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new NotFoundException("Remboursement introuvable"));
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            throw new ConflictException("Ce remboursement est deja effectue");
        }
        if (refund.getStatus() == RefundStatus.PROCESSING) {
            throw new ConflictException("Ce remboursement est en cours d execution");
        }
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId()).orElse(refund.getPayment());
        RefundStatus previous = refund.getStatus();
        PaymentStatus previousPayment = payment.getStatus();
        Instant now = Instant.now();
        String trimmed = reason.trim();
        refund.setStatus(RefundStatus.FAILED);
        refund.setLastError(trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed);
        refund.setCompletedAt(now);
        refund.setUpdatedAt(now);
        refundRepository.save(refund);
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setRefundLastError(refund.getLastError());
        paymentRepository.save(payment);
        paymentEventService.record(payment, PaymentEventService.REFUND_ABANDONED, previousPayment, PaymentStatus.SUCCEEDED,
                PaymentEventSource.ADMIN, adminId,
                PaymentEventService.details("refundId", refund.getId().toString(), "from", previous.name(), "reason", trimmed));
        auditService.log(adminId, "PAYMENT_REFUND_ABANDONED", "payment", payment.getId(),
                Map.of("refundId", refund.getId().toString(), "reason", trimmed, "previousStatus", previous.name()));
        return AdminRefundResponse.from(refund);
    }

    /* --------------------------------------------- compatibilite /api/v1/admin/payments/** */

    /** Relance depuis l identifiant du paiement (route historique) : agit sur son remboursement vivant. */
    public AdminPaymentResponse retryNow(UUID adminId, UUID paymentId) {
        UUID refundId = tx.execute(status -> liveRefundIdOf(paymentId));
        retry(adminId, refundId);
        return tx.execute(status -> toAdminPayment(refundRepository.findById(refundId).orElseThrow()));
    }

    /** Marquage depuis l identifiant du paiement (route historique). */
    @Transactional
    public AdminPaymentResponse markRefunded(UUID adminId, UUID paymentId, String note) {
        UUID refundId = liveRefundIdOf(paymentId);
        markSucceeded(adminId, refundId, note);
        return toAdminPayment(refundRepository.findById(refundId).orElseThrow());
    }

    private UUID liveRefundIdOf(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
        return refundRepository.findLiveByPaymentId(payment.getId()).stream()
                .filter(r -> OPEN_STATUSES.contains(r.getStatus()))
                .findFirst().map(Refund::getId)
                .orElseThrow(() -> new BadRequestException("Ce paiement n attend pas de remboursement"));
    }

    static AdminPaymentResponse toAdminPayment(Refund r) {
        Payment p = r.getPayment();
        Booking b = p.getBooking();
        User passenger = b != null ? b.getPassenger() : null;
        return new AdminPaymentResponse(p.getId(), b != null ? b.getId() : null,
                p.getSubscription() != null ? p.getSubscription().getId() : null,
                passenger != null ? passenger.getId() : null,
                passenger != null ? passenger.getFirstName() + " " + passenger.getLastName() : null,
                passenger != null ? passenger.getPhone() : null,
                p.getProviderTxId(), p.getAmount(), p.getStatus(), r.getAmountFcfa(), r.getReason(),
                r.getCreatedAt(), r.getAttempts(), r.getLastError(),
                r.getStatus() == RefundStatus.SUCCEEDED ? r.getCompletedAt() : null, p.getCreatedAt());
    }
}
