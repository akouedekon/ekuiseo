package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Remboursements Kkiapay en deux temps (constats F004, F036, F105, F106 de l audit).
 *
 * <ol>
 *   <li><b>Decision</b>, dans la transaction metier (annulation, paiement orphelin) :
 *       le paiement passe {@code REFUND_PENDING} avec montant et motif, la reservation
 *       est retiree du lot de reversement en attente le cas echeant, le passager est
 *       prevenu. Rien ne sort vers l agregateur : un echec reseau ne peut plus annuler
 *       une cascade d annulations deja decidees.</li>
 *   <li><b>Execution</b>, apres validation de la transaction (afterCommit) puis par
 *       reprise planifiee : appel {@code /transactions/revert}, puis ecriture courte du
 *       resultat. Au-dela de {@code ekuiseo.refund.max-attempts} echecs, ou pour un
 *       montant partiel (non supporte par l API Kkiapay), le paiement passe
 *       {@code REFUND_MANUAL} et apparait dans la file du back-office.</li>
 * </ol>
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    public static final String REASON_ORPHAN = "PAYMENT_ORPHAN";
    public static final String REASON_AMOUNT_INSUFFICIENT = "AMOUNT_INSUFFICIENT";

    private final PaymentRepository paymentRepository;
    private final KkiapayGateway kkiapayGateway;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final PayoutService payoutService;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final int maxAttempts;

    public RefundService(PaymentRepository paymentRepository, KkiapayGateway kkiapayGateway, AuditService auditService,
                         NotificationService notificationService, PayoutService payoutService,
                         PlatformTransactionManager transactionManager,
                         @Qualifier("refundExecutor") Executor executor,
                         @Value("${ekuiseo.refund.max-attempts:5}") int maxAttempts) {
        this.paymentRepository = paymentRepository;
        this.kkiapayGateway = kkiapayGateway;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.payoutService = payoutService;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.maxAttempts = maxAttempts;
    }

    public enum RequestStatus { REQUESTED, MANUAL_REQUIRED, NOT_APPLICABLE, NO_PAYMENT }

    public record RequestOutcome(RequestStatus status, String message) {
    }

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
            log.warn("Remboursement demande pour la reservation {} mais aucun paiement SUCCEEDED trouve", booking.getId());
            auditService.log(null, "REFUND_NO_PAYMENT_FOUND", "booking", booking.getId(), Map.of("reason", reason));
            return new RequestOutcome(RequestStatus.NO_PAYMENT, "Aucun paiement reussi trouve pour cette reservation");
        }
        Payment payment = succeeded.get();
        return mark(payment, booking.getPassenger(), Math.min(refundAmountFcfa, payment.getAmount()), reason);
    }

    /**
     * Paiement verifie SUCCEEDED alors que la reservation n est plus en attente (expiree,
     * annulee) ou montant insuffisant : l argent encaisse repart integralement. A appeler
     * dans la transaction qui vient de poser SUCCEEDED sur le paiement.
     */
    @Transactional
    public RequestOutcome requestForOrphanPayment(Payment payment, User passenger, String reason, long paidAmountFcfa) {
        if (paidAmountFcfa <= 0) {
            return new RequestOutcome(RequestStatus.NOT_APPLICABLE, "Aucun montant encaisse");
        }
        // On rembourse tout ce que Kkiapay a reellement encaisse (montant verifie), meme s il
        // etait inferieur a l attendu : le revert Kkiapay porte sur la transaction entiere.
        return mark(payment, passenger, paidAmountFcfa, paidAmountFcfa, reason);
    }

    private RequestOutcome mark(Payment payment, User passenger, long amount, String reason) {
        return mark(payment, passenger, amount, payment.getAmount(), reason);
    }

    private RequestOutcome mark(Payment payment, User passenger, long amount, long paidAmount, String reason) {
        payment.setRefundAmount(amount);
        payment.setRefundReason(reason);
        payment.setRefundRequestedAt(Instant.now());
        boolean partial = amount < paidAmount;
        boolean noGatewayId = payment.getProviderTxId() == null || payment.getProviderTxId().startsWith("ekuiseo-");
        UUID bookingId = payment.getBooking() != null ? payment.getBooking().getId() : null;
        if (partial || noGatewayId) {
            payment.setStatus(PaymentStatus.REFUND_MANUAL);
            payment.setRefundLastError(partial
                    ? "Remboursement partiel (" + amount + "/" + paidAmount + " FCFA) : l API Kkiapay ne rembourse que le montant total"
                    : "Aucun identifiant de transaction Kkiapay sur ce paiement");
            paymentRepository.save(payment);
            auditService.log(null, "REFUND_MANUAL_REQUIRED", "payment", payment.getId(),
                    Map.of("reason", reason, "refundAmountFcfa", amount, "paidFcfa", paidAmount,
                            "bookingId", String.valueOf(bookingId), "cause", partial ? "partial" : "no-gateway-id"));
            notifyPending(passenger, payment, amount, true);
            return new RequestOutcome(RequestStatus.MANUAL_REQUIRED,
                    "Remboursement de " + amount + " FCFA a traiter manuellement par le back-office");
        }
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        paymentRepository.save(payment);
        auditService.log(null, "PAYMENT_REFUND_REQUESTED", "payment", payment.getId(),
                Map.of("reason", reason, "refundAmountFcfa", amount, "bookingId", String.valueOf(bookingId)));
        notifyPending(passenger, payment, amount, false);
        scheduleAfterCommit(payment.getId());
        return new RequestOutcome(RequestStatus.REQUESTED, "Remboursement de " + amount + " FCFA demande a l agregateur");
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
    private void scheduleAfterCommit(UUID paymentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> process(paymentId));
                }
            });
        } else {
            executor.execute(() -> process(paymentId));
        }
    }

    /**
     * Execute un remboursement en attente : appel Kkiapay HORS transaction, puis ecriture
     * courte du resultat. Idempotent : ne fait rien si le paiement n est plus REFUND_PENDING.
     */
    public void process(UUID paymentId) {
        String txId = tx.execute(status -> paymentRepository.findById(paymentId)
                .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                .map(Payment::getProviderTxId)
                .orElse(null));
        if (txId == null) {
            return;
        }
        KkiapayGateway.RefundResult result;
        try {
            result = kkiapayGateway.refundTransaction(txId);
        } catch (RuntimeException ex) {
            result = new KkiapayGateway.RefundResult(false, "EXCEPTION", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        final KkiapayGateway.RefundResult outcome = result;
        tx.executeWithoutResult(status -> paymentRepository.findById(paymentId)
                .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                .ifPresent(payment -> record(payment, outcome)));
    }

    private void record(Payment payment, KkiapayGateway.RefundResult result) {
        UUID bookingId = payment.getBooking() != null ? payment.getBooking().getId() : null;
        User passenger = payment.getBooking() != null ? payment.getBooking().getPassenger() : null;
        if (result.success()) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setRefundedAt(Instant.now());
            payment.setRefundLastError(null);
            paymentRepository.save(payment);
            auditService.log(null, "PAYMENT_REFUNDED", "payment", payment.getId(),
                    Map.of("amountFcfa", payment.getRefundAmount(), "bookingId", String.valueOf(bookingId),
                            "gatewayMessage", String.valueOf(result.message())));
            if (passenger != null) {
                notificationService.notify(passenger, NotificationType.PAYMENT_REFUNDED, Map.of(
                        "paymentId", payment.getId().toString(), "bookingId", String.valueOf(bookingId),
                        "amountFcfa", payment.getRefundAmount()));
            }
            log.info("Remboursement Kkiapay confirme pour le paiement {} ({} FCFA)", payment.getId(), payment.getRefundAmount());
            return;
        }
        int attempts = payment.getRefundAttempts() + 1;
        payment.setRefundAttempts(attempts);
        String error = String.valueOf(result.message());
        payment.setRefundLastError(error.length() > 500 ? error.substring(0, 500) : error);
        if (attempts >= maxAttempts) {
            payment.setStatus(PaymentStatus.REFUND_MANUAL);
            auditService.log(null, "REFUND_MANUAL_REQUIRED", "payment", payment.getId(),
                    Map.of("cause", "gateway-failed", "attempts", attempts, "lastError", error,
                            "bookingId", String.valueOf(bookingId)));
            log.error("Remboursement du paiement {} abandonne apres {} tentatives : {}", payment.getId(), attempts, error);
        } else {
            log.warn("Remboursement du paiement {} echoue (tentative {}/{}) : {}", payment.getId(), attempts, maxAttempts, error);
        }
        paymentRepository.save(payment);
    }

    /** Reprise planifiee des remboursements en attente depuis plus de {@code before}. */
    public int retryPending(Instant before) {
        List<UUID> ids = tx.execute(status -> paymentRepository
                .findByStatusAndRefundRequestedAtBefore(PaymentStatus.REFUND_PENDING, before)
                .stream().map(Payment::getId).toList());
        if (ids == null) return 0;
        ids.forEach(this::process);
        return ids.size();
    }

    /* ------------------------------------------------------------------ back-office */

    @Transactional(readOnly = true)
    public List<AdminPaymentResponse> listForAdmin(String statusFilter) {
        List<PaymentStatus> statuses = switch (statusFilter == null ? "" : statusFilter.toUpperCase()) {
            case "REFUND_PENDING" -> List.of(PaymentStatus.REFUND_PENDING);
            case "REFUND_MANUAL" -> List.of(PaymentStatus.REFUND_MANUAL);
            case "REFUNDED" -> List.of(PaymentStatus.REFUNDED);
            case "ALL" -> List.of(PaymentStatus.values());
            default -> List.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_MANUAL);
        };
        return paymentRepository.findForAdmin(statuses).stream().map(this::toAdmin).toList();
    }

    /** Relance immediate (synchrone) d un remboursement en attente ou manuel : l admin voit le resultat. */
    public AdminPaymentResponse retryNow(UUID adminId, UUID paymentId) {
        tx.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
            if (payment.getStatus() != PaymentStatus.REFUND_PENDING && payment.getStatus() != PaymentStatus.REFUND_MANUAL) {
                throw new BadRequestException("Ce paiement n attend pas de remboursement");
            }
            if (payment.getProviderTxId() == null || payment.getProviderTxId().startsWith("ekuiseo-")) {
                throw new BadRequestException("Aucun identifiant Kkiapay sur ce paiement : remboursez depuis le tableau de bord Kkiapay puis marquez-le rembourse");
            }
            if (payment.getRefundAmount() != null && payment.getRefundAmount() < payment.getAmount()) {
                throw new BadRequestException("Montant partiel : remboursez depuis le tableau de bord Kkiapay puis marquez-le rembourse");
            }
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setRefundAttempts(0);
            paymentRepository.save(payment);
            auditService.log(adminId, "PAYMENT_REFUND_RETRIED", "payment", paymentId, Map.of());
        });
        process(paymentId);
        return tx.execute(status -> toAdmin(paymentRepository.findById(paymentId).orElseThrow()));
    }

    /** L admin a rembourse a la main (tableau de bord Kkiapay, virement) : on l enregistre, journalise et prevenons le passager. */
    @Transactional
    public AdminPaymentResponse markRefunded(UUID adminId, UUID paymentId, String note) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING && payment.getStatus() != PaymentStatus.REFUND_MANUAL) {
            throw new BadRequestException("Ce paiement n attend pas de remboursement");
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(Instant.now());
        payment.setRefundLastError(null);
        paymentRepository.save(payment);
        auditService.log(adminId, "PAYMENT_MARKED_REFUNDED", "payment", paymentId,
                Map.of("note", note == null ? "" : note, "amountFcfa",
                        payment.getRefundAmount() == null ? payment.getAmount() : payment.getRefundAmount()));
        if (payment.getBooking() != null) {
            notificationService.notify(payment.getBooking().getPassenger(), NotificationType.PAYMENT_REFUNDED, Map.of(
                    "paymentId", payment.getId().toString(), "bookingId", payment.getBooking().getId().toString(),
                    "amountFcfa", payment.getRefundAmount() == null ? payment.getAmount() : payment.getRefundAmount()));
        }
        return toAdmin(payment);
    }

    private AdminPaymentResponse toAdmin(Payment p) {
        Booking b = p.getBooking();
        User passenger = b != null ? b.getPassenger() : null;
        return new AdminPaymentResponse(p.getId(), b != null ? b.getId() : null,
                p.getSubscription() != null ? p.getSubscription().getId() : null,
                passenger != null ? passenger.getId() : null,
                passenger != null ? passenger.getFirstName() + " " + passenger.getLastName() : null,
                passenger != null ? passenger.getPhone() : null,
                p.getProviderTxId(), p.getAmount(), p.getStatus(), p.getRefundAmount(), p.getRefundReason(),
                p.getRefundRequestedAt(), p.getRefundAttempts(), p.getRefundLastError(), p.getRefundedAt(), p.getCreatedAt());
    }
}
