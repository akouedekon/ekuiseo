package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.PaymentChannel;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiateDepositRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.payment.KkiapayWebhookPayload;
import bj.ekuiseo.api.dto.payment.PaymentClientStatus;
import bj.ekuiseo.api.dto.payment.PaymentStatusResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.service.kkiapay.KkiapayGateway;
import bj.ekuiseo.api.service.kkiapay.KkiapayUnavailableException;
import bj.ekuiseo.api.service.kkiapay.KkiapayWebhookParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration avec l'agregateur Kkiapay (mobile money : MTN MoMo, Moov Money, Celtiis
 * Cash ; carte ; Wave). Le paiement lui-meme est initie par le widget Kkiapay cote
 * frontend (cle publique) : ce service (1) prepare une reference de correlation pour ce
 * widget, (2) verifie CHAQUE evenement webhook aupres de l'API Kkiapay elle-meme avant
 * d'en tenir compte (jamais confiance au seul payload webhook, meme signe), et
 * (3) declenche les remboursements a l'annulation. Voir {@link KkiapayGateway} pour le
 * detail (confirme / a valider) du contrat Kkiapay.
 *
 * <p>Phase 3 (constats F012/F149/F019/F150/F151/F140) : un seul paiement INITIATED par
 * reservation ou abonnement (reutilise par une nouvelle ouverture du widget et par le
 * webhook), verrou pessimiste sur le paiement pendant sa confirmation, montant verifie
 * enregistre et surpaiement journalise, operateur reel lu dans la verification, webhook
 * acquitte (jamais 404) quand il ne se rattache a rien.</p>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Un paiement INITIATED sans identifiant Kkiapay au-dela de ce delai est abandonne (regle metier n.6). */
    public static final long ABANDON_DELAY_MINUTES = 20;

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final KkiapayGateway kkiapayGateway;
    private final RefundService refundService;
    private final KkiapayWebhookParser webhookParser;
    private final DriverApprovalPolicy driverApprovalPolicy;
    private final String publicKey;
    private final String webhookSecret;
    private final boolean sandbox;

    public PaymentService(PaymentRepository paymentRepository, BookingRepository bookingRepository,
                           DriverSubscriptionRepository driverSubscriptionRepository,
                           NotificationService notificationService, AuditService auditService,
                           KkiapayGateway kkiapayGateway, RefundService refundService,
                           KkiapayWebhookParser webhookParser, DriverApprovalPolicy driverApprovalPolicy,
                           @Value("${ekuiseo.kkiapay.public-key:}") String publicKey,
                           @Value("${ekuiseo.kkiapay.webhook-secret:}") String webhookSecret,
                           @Value("${ekuiseo.kkiapay.sandbox:true}") boolean sandbox) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.kkiapayGateway = kkiapayGateway;
        this.refundService = refundService;
        this.webhookParser = webhookParser;
        this.driverApprovalPolicy = driverApprovalPolicy;
        this.publicKey = publicKey;
        this.webhookSecret = webhookSecret;
        this.sandbox = sandbox;
    }

    /**
     * Prepare (ou reprend) le paiement d une reservation. Le dernier paiement INITIATED de
     * la reservation est reutilise (constat F019) : fermer puis rouvrir le widget ne cree
     * plus une ligne par tentative ; un nouveau paiement n est cree qu apres un FAILED.
     */
    @Transactional
    public InitiatePaymentResponse initiate(UUID passengerId, InitiatePaymentRequest req) {
        Booking booking = bookingRepository.findById(req.bookingId())
                .orElseThrow(() -> new NotFoundException("Reservation introuvable"));
        if (!booking.getPassenger().getId().equals(passengerId)) {
            throw new ForbiddenException("Cette reservation ne vous appartient pas");
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Cette reservation n est pas en attente de paiement");
        }
        // Le widget USSD peut prendre plus d une minute : si l echeance est proche, on la
        // prolonge pour que le scheduler d expiration et le paiement ne se croisent plus (F036).
        Instant now = Instant.now();
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now.plus(5, ChronoUnit.MINUTES))) {
            booking.setExpiresAt(now.plus(10, ChronoUnit.MINUTES));
            bookingRepository.save(booking);
        }
        // Regle metier n.21 : on initie ici deposit_amount, pas amount - c'est la partie
        // reellement prelevee en ligne (la totalite en MOMO_FULL, un acompte en
        // MOMO_DEPOSIT, deja calcule et fige a la creation de la reservation, voir
        // BookingService#createBooking et FeePolicy#computeDepositAmount). balance_due_on_board
        // (le cas echeant) est regle en especes au conducteur pendant le trajet, jamais via Kkiapay.
        long amount = booking.getDepositAmount();
        Payment payment = paymentRepository
                .findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED)
                .orElseGet(() -> paymentRepository.save(Payment.builder()
                        .booking(booking)
                        .provider(PaymentProvider.KKIAPAY)
                        .providerTxId("ekuiseo-booking-" + UUID.randomUUID())
                        .amount(amount)
                        .status(PaymentStatus.INITIATED)
                        .build()));
        return new InitiatePaymentResponse(payment.getId(), payment.getProviderTxId(), payment.getAmount(), publicKey, sandbox,
                Map.of("bookingId", booking.getId().toString()));
    }

    /**
     * POST /api/v1/bookings/{id}/payments/deposit : voie normale pour initier le
     * paiement d'une reservation. Delegue entierement a {@link #initiate} (memes
     * verifications, meme charge utile) puis attache, au mieux-effort, l'operateur mobile
     * money indique par le passager au paiement - ce pre-remplissage est ecrase par
     * l operateur reel des que la verification Kkiapay arrive (constat F140).
     */
    @Transactional
    public InitiatePaymentResponse initiateDeposit(UUID bookingId, UUID passengerId, InitiateDepositRequest req) {
        InitiatePaymentResponse response = initiate(passengerId, new InitiatePaymentRequest(bookingId));
        paymentRepository.findById(response.paymentId()).ifPresent(payment -> {
            payment.setChannel(toChannel(req.provider()));
            paymentRepository.save(payment);
        });
        return response;
    }

    /**
     * GET /api/v1/payments/{paymentId} : etat d'un paiement pour sondage cote
     * front en attendant le webhook (regle metier n.3). Reserve au passager de la
     * reservation concernee.
     */
    @Transactional(readOnly = true)
    public PaymentStatusResponse getStatus(UUID paymentId, UUID requesterId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
        assertOwner(payment, requesterId);
        return toStatusResponse(payment);
    }

    /**
     * Un paiement appartient au passager de sa reservation, ou au conducteur de
     * son abonnement (regle metier n.11) : personne d'autre ne peut le consulter
     * ni le confirmer.
     */
    private void assertOwner(Payment payment, UUID requesterId) {
        Booking booking = payment.getBooking();
        DriverSubscription subscription = payment.getSubscription();
        boolean owner = booking != null
                ? booking.getPassenger().getId().equals(requesterId)
                : subscription != null && subscription.getDriver().getId().equals(requesterId);
        if (!owner) {
            throw new ForbiddenException("Ce paiement ne vous appartient pas");
        }
    }

    private PaymentStatusResponse toStatusResponse(Payment payment) {
        Booking booking = payment.getBooking();
        DriverSubscription subscription = payment.getSubscription();
        PaymentClientStatus status = mapStatusForClient(payment, booking);
        String instruction = status == PaymentClientStatus.PROCESSING
                ? "Composez le code USSD de votre operateur mobile money et validez avec votre code secret."
                : null;
        Instant updatedAt = payment.getUpdatedAt() != null ? payment.getUpdatedAt() : payment.getCreatedAt();
        return new PaymentStatusResponse(payment.getId(),
                booking != null ? booking.getId() : null,
                subscription != null ? subscription.getId() : null,
                payment.getProviderTxId(), mapProviderForClient(payment.getChannel()), status, payment.getAmount(),
                instruction, updatedAt);
    }

    /**
     * POST /api/v1/payments/{paymentId}/confirm : le widget Kkiapay vient d'emettre son
     * evenement "success" cote frontend, avec l'identifiant de transaction Kkiapay.
     * Chemin de confirmation <b>complementaire</b> au webhook (qui reste la source de
     * verite en cas de fermeture du navigateur) : il rend la confirmation immediate au
     * lieu de dependre du delai et de la bonne configuration du webhook.
     *
     * <p>Meme rigueur que {@link #handleWebhook} : rien n'est cru sur parole. La
     * transaction est reverifiee aupres de l'API Kkiapay, son montant doit couvrir le
     * montant attendu (sinon un passager pourrait ouvrir le widget avec 5 F et confirmer
     * une reservation a 4 000 F), et l'identifiant Kkiapay remplace la reference interne
     * dans {@code provider_tx_id} - ce qui rend le webhook ulterieur idempotent (il
     * retrouvera ce paiement deja SUCCEEDED et s'arretera la). Le paiement est charge
     * sous verrou (constat F150) : widget et webhook ne se confirment plus en parallele.</p>
     */
    @Transactional
    public PaymentStatusResponse confirmFromWidget(UUID paymentId, UUID requesterId, String transactionId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
        assertOwner(payment, requesterId);
        Booking booking = payment.getBooking();
        DriverSubscription subscription = payment.getSubscription();
        if (transactionId == null || transactionId.isBlank()) {
            throw new BadRequestException("transactionId manquant");
        }
        String txId = transactionId.trim();
        if (isTerminal(payment.getStatus())) {
            // Deja confirme (webhook passe avant nous), rembourse, ou refuse definitivement :
            // on ne reverifie rien, l etat courant fait foi (constat F130).
            return toStatusResponse(payment);
        }

        // Course avec le webhook : il a pu creer/renseigner un paiement portant deja ce
        // transactionId pour le meme objet. On renvoie alors son etat, sans rien refaire.
        Optional<Payment> byTx = paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, txId);
        if (byTx.isPresent() && !byTx.get().getId().equals(payment.getId())) {
            Payment other = byTx.get();
            boolean sameTarget = booking != null
                    ? other.getBooking() != null && other.getBooking().getId().equals(booking.getId())
                    : other.getSubscription() != null && subscription != null
                            && other.getSubscription().getId().equals(subscription.getId());
            if (!sameTarget) {
                throw new BadRequestException("Cette transaction ne correspond pas a ce paiement");
            }
            return toStatusResponse(other);
        }

        KkiapayGateway.VerificationResult verified = kkiapayGateway.verifyTransaction(txId);
        payment.setProviderTxId(txId);
        applyOperator(payment, verified, null);
        Decision decision = applyVerification(payment, verified, payment.getAmount(), "widget-confirm");
        paymentRepository.save(payment);
        applyDecision(payment, verified, decision);
        return toStatusResponse(payment);
    }

    /** Issue d une verification Kkiapay (constat F011) : partagee par le widget et le webhook. */
    enum Decision { SUCCEEDED, FAILED, PENDING }

    /**
     * Applique le verdict de Kkiapay au paiement, sans decider du sort de la reservation ou de
     * l abonnement (voir {@link #applyDecision}). Un succes au montant insuffisant est un FAILED
     * (l argent encaisse sera rembourse) ; un etat non conclusif (transaction en cours, reponse
     * vide, erreur HTTP transitoire) laisse le paiement INITIATED. Les frais, le montant verifie
     * (constat F151) et le detail brut sont consignes dans tous les cas ; un surpaiement est
     * accepte, journalise et audite (PAYMENT_OVERPAID), jamais rembourse d office : l API
     * Kkiapay ne rembourse pas partiellement, c est au back-office de trancher.
     */
    Decision applyVerification(Payment payment, KkiapayGateway.VerificationResult verified, long expectedAmount, String source) {
        boolean amountOk = isAmountSufficient(verified, expectedAmount);
        Decision decision;
        if (verified.success()) {
            decision = amountOk ? Decision.SUCCEEDED : Decision.FAILED;
        } else {
            decision = isFinalFailure(verified) ? Decision.FAILED : Decision.PENDING;
        }
        payment.setFee(verified.feesFcfa());
        payment.setVerifiedAmount(verified.amountFcfa());
        Map<String, Object> raw = new LinkedHashMap<>();
        if (payment.getRawPayload() != null) {
            raw.putAll(payment.getRawPayload());
        }
        raw.put("source", source);
        raw.put("verifiedStatus", String.valueOf(verified.rawStatus()));
        raw.put("verifiedAmount", String.valueOf(verified.amountFcfa()));
        raw.put("expectedAmount", String.valueOf(expectedAmount));
        raw.put("amountSufficient", String.valueOf(amountOk));
        raw.put("operator", String.valueOf(verified.operator()));
        raw.put("decision", decision.name());
        payment.setRawPayload(raw);
        if (decision == Decision.SUCCEEDED) {
            payment.setStatus(PaymentStatus.SUCCEEDED);
            if (verified.amountFcfa() > expectedAmount) {
                long excess = verified.amountFcfa() - expectedAmount;
                log.warn("Surpaiement Kkiapay {} : {} F verifies pour {} F attendus (excedent {} F), a traiter par le back-office",
                        payment.getProviderTxId(), verified.amountFcfa(), expectedAmount, excess);
                Map<String, Object> details = new HashMap<>();
                details.put("transactionId", String.valueOf(payment.getProviderTxId()));
                details.put("expectedAmountFcfa", expectedAmount);
                details.put("verifiedAmountFcfa", verified.amountFcfa());
                details.put("excessFcfa", excess);
                details.put("source", source);
                auditService.log(null, "PAYMENT_OVERPAID", "payment", payment.getId(), details);
            }
        } else if (decision == Decision.FAILED) {
            payment.setStatus(PaymentStatus.FAILED);
        }
        return decision;
    }

    /**
     * Consequences d une decision sur l objet paye : remboursement d un montant insuffisant
     * (F105), confirmation ou echec de la reservation / de l abonnement. Rien n est fait en
     * PENDING : le webhook (ou un rejeu du webhook) tranchera.
     */
    private void applyDecision(Payment payment, KkiapayGateway.VerificationResult verified, Decision decision) {
        if (decision == Decision.PENDING) {
            return;
        }
        Booking booking = payment.getBooking();
        DriverSubscription subscription = payment.getSubscription();
        if (verified.success() && decision == Decision.FAILED) {
            // L argent a ete encaisse mais ne couvre pas l attendu : il repart au passager (F105).
            log.warn("Paiement Kkiapay {} d un montant insuffisant ({} F verifies pour {} F attendus) : non confirme, remboursement demande.",
                    payment.getProviderTxId(), verified.amountFcfa(), payment.getAmount());
            refundService.requestForOrphanPayment(payment, booking != null ? booking.getPassenger() : null,
                    RefundService.REASON_AMOUNT_INSUFFICIENT, verified.amountFcfa());
        }
        boolean succeeded = decision == Decision.SUCCEEDED;
        if (booking != null) {
            handleBookingPaymentResult(payment, booking, succeeded);
        } else if (subscription != null) {
            handleSubscriptionPaymentResult(subscription, succeeded);
        }
    }

    /**
     * Etats qu aucun evenement ulterieur ne doit ecraser (constat F130) : un paiement confirme,
     * rembourse ou en cours de remboursement, ou refuse definitivement. Un rejeu de webhook sur
     * l un d eux est ignore et journalise.
     */
    static boolean isTerminal(PaymentStatus status) {
        return status != PaymentStatus.INITIATED;
    }

    /**
     * Le montant reellement verifie chez Kkiapay doit couvrir le montant attendu. Un
     * montant verifie a 0 signifie que l'API ne l'a pas renvoye : on ne peut pas
     * conclure, on refuse (mieux vaut un passager qui attend le webhook qu'une
     * reservation confirmee pour 5 F).
     */
    private boolean isAmountSufficient(KkiapayGateway.VerificationResult verified, long expectedAmount) {
        return verified.amountFcfa() > 0 && verified.amountFcfa() >= expectedAmount;
    }

    /**
     * Distingue un echec definitif (Kkiapay repond FAILED/REFUSED...) d'un etat non
     * conclusif (transaction encore en cours, reponse vide, erreur HTTP transitoire) :
     * dans le second cas le paiement reste INITIATED et le webhook tranchera.
     */
    private boolean isFinalFailure(KkiapayGateway.VerificationResult verified) {
        String raw = verified.rawStatus() == null ? "" : verified.rawStatus().toUpperCase();
        if (raw.isEmpty() || raw.equals("EMPTY_RESPONSE") || raw.startsWith("HTTP_")) {
            return false;
        }
        return !(raw.contains("PENDING") || raw.contains("PROCESSING") || raw.contains("INITIATED")
                || raw.contains("WAITING"));
    }

    /**
     * Vocabulaire client ({@link PaymentClientStatus}) distinct du vocabulaire interne
     * {@link PaymentStatus} : INITIATED devient EXPIRED si la reservation liee a deja ete
     * annulee/expiree pendant l'attente du webhook (regle metier n.2), sinon PROCESSING ;
     * un remboursement en cours ou termine est montre comme tel (constats F105/F501).
     */
    private PaymentClientStatus mapStatusForClient(Payment payment, Booking booking) {
        return switch (payment.getStatus()) {
            case INITIATED -> booking != null && (booking.getStatus() == BookingStatus.CANCELLED_BY_PASSENGER
                    || booking.getStatus() == BookingStatus.CANCELLED_BY_DRIVER
                    || booking.getStatus() == BookingStatus.EXPIRED)
                    ? PaymentClientStatus.EXPIRED : PaymentClientStatus.PROCESSING;
            case SUCCEEDED -> PaymentClientStatus.SUCCEEDED;
            case FAILED -> PaymentClientStatus.FAILED;
            // Argent encaisse mais reservation perdue (ou annulee) : le client doit voir le
            // remboursement, pas une place confirmee (F105).
            case REFUND_PENDING, REFUND_MANUAL -> PaymentClientStatus.REFUND_PENDING;
            case REFUNDED -> PaymentClientStatus.REFUNDED;
        };
    }

    private String mapProviderForClient(PaymentChannel channel) {
        if (channel == null) return null;
        return switch (channel) {
            case MTN -> "MTN_MOMO";
            case MOOV -> "MOOV_MONEY";
            case CELTIIS -> "CELTIIS_CASH";
            case CARD -> null;
        };
    }

    private PaymentChannel toChannel(MobileMoneyOperator operator) {
        if (operator == null) return null;
        return switch (operator) {
            case MTN_MOMO -> PaymentChannel.MTN;
            case MOOV_MONEY -> PaymentChannel.MOOV;
            case CELTIIS_CASH -> PaymentChannel.CELTIIS;
        };
    }

    /**
     * Prepare (ou reprend) le paiement d un abonnement conducteur (regle metier n.11) :
     * comme pour une reservation, le paiement INITIATED existant est reutilise (F149).
     */
    @Transactional
    public InitiatePaymentResponse initiateSubscriptionPayment(DriverSubscription subscription) {
        Payment payment = paymentRepository
                .findFirstBySubscriptionIdAndStatusOrderByCreatedAtDesc(subscription.getId(), PaymentStatus.INITIATED)
                .orElseGet(() -> paymentRepository.save(Payment.builder()
                        .subscription(subscription)
                        .provider(PaymentProvider.KKIAPAY)
                        .providerTxId("ekuiseo-subscription-" + UUID.randomUUID())
                        .amount(subscription.getPriceFcfa())
                        .status(PaymentStatus.INITIATED)
                        .build()));
        return new InitiatePaymentResponse(payment.getId(), payment.getProviderTxId(), payment.getAmount(), publicKey,
                sandbox, Map.of("subscriptionId", subscription.getId().toString()));
    }

    /**
     * Verifie l'en-tete {@code X-Kkiapay-Secret} du webhook. D'apres la documentation
     * Kkiapay ("Webhook | KKIAPAY"), cet en-tete transporte directement le "secret hash"
     * configure dans le tableau de bord marchand (PAS un HMAC calcule sur le corps) :
     * la verification est donc une simple comparaison en temps constant.
     */
    public boolean verifySignature(String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("KKIAPAY_WEBHOOK_SECRET non configure : webhook refuse. Renseignez cette variable "
                    + "avec le secret hash configure dans le tableau de bord Kkiapay (menu Webhook).");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        return constantTimeEquals(webhookSecret, signatureHeader.trim());
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Traite le webhook Kkiapay. Idempotent via provider_tx_id (contrainte unique en base).
     * Ne fait JAMAIS confiance au seul champ {@code isPaymentSucces} du payload : l'etat
     * effectif de la transaction est reconfirme par un appel serveur a serveur a l'API
     * Kkiapay (regle metier n.3 durcie) - mais seulement une fois la cible resolue
     * (constat F012) : un evenement qui ne se rattache a rien (reservation ou abonnement
     * inconnu, stateData vide) est journalise et acquitte, jamais 404, pour ne pas payer une
     * verification ni declencher des reessais Kkiapay infinis. 400 reste reserve aux payloads
     * invalides (transactionId absent) et 503 aux verifications non conclusives.
     */
    // noRollbackFor : un etat non conclusif chez Kkiapay est signale en 503 (Kkiapay rejoue le
    // webhook) SANS perdre l identifiant de transaction inscrit sur le paiement INITIATED.
    @Transactional(noRollbackFor = KkiapayUnavailableException.class)
    public void handleWebhook(KkiapayWebhookPayload payload) {
        if (payload.transactionId() == null || payload.transactionId().isBlank()) {
            throw new BadRequestException("transactionId manquant dans le webhook Kkiapay");
        }
        Optional<Payment> existing = paymentRepository.findByProviderAndProviderTxId(PaymentProvider.KKIAPAY, payload.transactionId());
        if (existing.isPresent() && isTerminal(existing.get().getStatus())) {
            // Deja confirme, rembourse (ou en cours de remboursement) ou refuse : un rejeu ne doit
            // jamais ecraser cet etat (constat F130).
            log.info("Webhook Kkiapay ignore (paiement deja {}) pour transactionId={}",
                    existing.get().getStatus(), payload.transactionId());
            return;
        }

        Payment payment;
        long expectedAmount;
        if (existing.isPresent()) {
            payment = lock(existing.get());
            expectedAmount = payment.getAmount();
        } else {
            UUID bookingId = webhookParser.extractBookingId(payload);
            UUID subscriptionId = webhookParser.extractSubscriptionId(payload);
            if (bookingId != null) {
                Booking booking = bookingRepository.findById(bookingId).orElse(null);
                if (booking == null) {
                    log.warn("Webhook Kkiapay acquitte sans suite : reservation {} inconnue (transactionId={})",
                            bookingId, payload.transactionId());
                    return;
                }
                // Le paiement INITIATED prepare par initiate() porte encore la reference interne
                // "ekuiseo-booking-..." : on le reutilise (et on y inscrit l'identifiant Kkiapay)
                // plutot que de creer une seconde ligne pour la meme reservation.
                payment = paymentRepository
                        .findFirstByBookingIdAndStatusOrderByCreatedAtDesc(booking.getId(), PaymentStatus.INITIATED)
                        .map(this::lock)
                        .orElseGet(() -> Payment.builder()
                                .booking(booking)
                                .provider(PaymentProvider.KKIAPAY)
                                // Repli sur deposit_amount (pas amount) : c'est la partie de la
                                // reservation reellement prelevee en ligne (regle metier n.21).
                                .amount(booking.getDepositAmount())
                                .status(PaymentStatus.INITIATED)
                                .build());
                payment.setProviderTxId(payload.transactionId());
                expectedAmount = booking.getDepositAmount();
            } else if (subscriptionId != null) {
                DriverSubscription subscription = driverSubscriptionRepository.findById(subscriptionId).orElse(null);
                if (subscription == null) {
                    log.warn("Webhook Kkiapay acquitte sans suite : abonnement {} inconnu (transactionId={})",
                            subscriptionId, payload.transactionId());
                    return;
                }
                // Meme reutilisation que pour une reservation (constat F149).
                payment = paymentRepository
                        .findFirstBySubscriptionIdAndStatusOrderByCreatedAtDesc(subscription.getId(), PaymentStatus.INITIATED)
                        .map(this::lock)
                        .orElseGet(() -> Payment.builder()
                                .subscription(subscription)
                                .provider(PaymentProvider.KKIAPAY)
                                .amount(subscription.getPriceFcfa())
                                .status(PaymentStatus.INITIATED)
                                .build());
                payment.setProviderTxId(payload.transactionId());
                expectedAmount = subscription.getPriceFcfa();
            } else {
                // Ni provider_tx_id connu ni correlation retrouvee dans stateData : on ne peut rien
                // rattacher cote applicatif. On journalise et on repond 2xx (deja fait par le
                // controller) pour ne pas declencher de reessais Kkiapay infinis sur un evenement
                // qui ne nous concerne pas (ex: autre marchand du meme compte, transaction de test).
                log.warn("Webhook Kkiapay recu sans correlation exploitable (stateData sans bookingId/subscriptionId), "
                        + "transactionId={}", payload.transactionId());
                return;
            }
        }

        // La cible est connue : la verification (payante, un appel HTTP) a lieu maintenant.
        KkiapayGateway.VerificationResult verified = kkiapayGateway.verifyTransaction(payload.transactionId());
        // Meme decision que confirmFromWidget (constat F011) : le montant est fixe par le widget
        // cote client, donc par l'utilisateur. Un montant verifie inferieur a l'attendu ne
        // confirme rien ; un etat non conclusif laisse le paiement INITIATED.
        applyOperator(payment, verified, payload.method());
        Decision decision = applyVerification(payment, verified, expectedAmount,
                "webhook:" + payload.event() + ":claimed=" + payload.paymentSucceeded());
        paymentRepository.save(payment);
        if (decision == Decision.PENDING) {
            // Ni succes ni echec definitif : on repond 503 pour que Kkiapay rejoue plus tard ;
            // rien n est notifie, l abonnement n est pas touche.
            log.warn("Webhook Kkiapay non conclusif ({}) pour transactionId={} : paiement laisse INITIATED, rejeu demande",
                    verified.rawStatus(), payload.transactionId());
            throw new KkiapayUnavailableException("Verification Kkiapay non conclusive : " + verified.rawStatus(), null);
        }
        applyDecision(payment, verified, decision);
    }

    /** Recharge un paiement persistant sous verrou (constat F150) ; un paiement encore transitoire est renvoye tel quel. */
    private Payment lock(Payment payment) {
        if (payment.getId() == null) {
            return payment;
        }
        return paymentRepository.findByIdForUpdate(payment.getId()).orElse(payment);
    }

    /**
     * Operateur reel (constat F140) : celui de la verification Kkiapay ({@code source_common_name}
     * / {@code source}) prime ; a defaut la methode declaree par le webhook, gardee de toute
     * facon dans raw_payload ({@code declaredMethod}). Une declaration du widget ou du passager
     * deja presente n est ecrasee que par une valeur reellement resolue.
     */
    private void applyOperator(Payment payment, KkiapayGateway.VerificationResult verified, String declaredMethod) {
        if (declaredMethod != null) {
            Map<String, Object> raw = new LinkedHashMap<>();
            if (payment.getRawPayload() != null) {
                raw.putAll(payment.getRawPayload());
            }
            raw.put("declaredMethod", declaredMethod);
            payment.setRawPayload(raw);
        }
        PaymentChannel channel = parseChannel(verified.operator());
        if (channel == null) {
            channel = parseChannel(declaredMethod);
        }
        if (channel != null) {
            payment.setChannel(channel);
        }
    }

    private void handleBookingPaymentResult(Payment payment, Booking booking, boolean success) {
        if (success) {
            if (booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.PENDING_DRIVER_APPROVAL) {
                return; // deja traitee (webhook et widget se sont croises)
            }
            if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
                // Les places ont deja ete liberees (expiration, annulation) : on ne peut pas
                // re-confirmer sans risquer une surreservation. L argent encaisse repart au
                // passager automatiquement (F036/F105), avec audit et notification.
                log.warn("Paiement Kkiapay recu pour la reservation {} en etat {} : non confirmee, remboursement demande.",
                        booking.getId(), booking.getStatus());
                refundService.requestForOrphanPayment(payment, booking.getPassenger(), RefundService.REASON_ORPHAN,
                        payment.getAmount());
                return;
            }
            Trip trip = booking.getTrip();
            // Trajet a accord conducteur (V19, point n.13) : l acompte est encaisse d abord, la place
            // reste bloquee, et le conducteur a un delai pour accepter ; sinon remboursement integral.
            boolean awaitingDriver = !trip.isInstantBooking();
            Instant now = Instant.now();
            booking.setStatus(awaitingDriver ? BookingStatus.PENDING_DRIVER_APPROVAL : BookingStatus.CONFIRMED);
            booking.setExpiresAt(null);
            booking.setApprovalDeadlineAt(awaitingDriver ? driverApprovalPolicy.deadline(now, trip.getDepartureAt()) : null);
            bookingRepository.save(booking);
            // Recu d acompte (constat F107) : montant encaisse, solde a bord, reference Kkiapay.
            Map<String, Object> receipt = new HashMap<>();
            receipt.put("bookingId", booking.getId().toString());
            receipt.put("tripId", trip.getId().toString());
            receipt.put("amountFcfa", payment.getAmount());
            receipt.put("totalFcfa", booking.getAmount());
            receipt.put("balanceDueOnBoardFcfa", booking.getBalanceDueOnBoard());
            receipt.put("reference", payment.getProviderTxId() == null ? "" : payment.getProviderTxId());
            receipt.put("route", trip.getOriginLabel() + " -> " + trip.getDestLabel());
            receipt.put("departureAt", Objects.toString(trip.getDepartureAt(), ""));
            if (awaitingDriver) {
                receipt.put("awaitingDriver", true);
                receipt.put("approvalDeadlineAt", Objects.toString(booking.getApprovalDeadlineAt(), ""));
            }
            notificationService.notifyCritical(booking.getPassenger(), NotificationType.PAYMENT_SUCCEEDED, receipt,
                    awaitingDriver
                            ? "Ekuiseo : votre paiement a ete recu, votre demande est transmise au conducteur. "
                                    + "Sans accord de sa part avant le " + BookingService.formatLocal(booking.getApprovalDeadlineAt())
                                    + ", vous serez rembourse integralement."
                            : "Ekuiseo : votre paiement a ete recu, votre reservation est confirmee."
                                    + (booking.getBalanceDueOnBoard() > 0
                                    ? " Solde a regler a bord : " + booking.getBalanceDueOnBoard() + " FCFA." : ""));
            Map<String, Object> driverPayload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                    "tripId", trip.getId().toString(),
                    "passengerName", booking.getPassenger().getFirstName(), "seats", booking.getSeats(),
                    "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                    "departureAt", Objects.toString(trip.getDepartureAt(), ""),
                    "approvalDeadlineAt", awaitingDriver ? booking.getApprovalDeadlineAt().toString() : null);
            if (awaitingDriver) {
                // Le conducteur doit repondre : e-mail critique, pas seulement une pastille in-app.
                notificationService.notifyCritical(trip.getDriver(), NotificationType.BOOKING_REQUESTED, driverPayload);
            } else {
                notificationService.notify(trip.getDriver(), NotificationType.BOOKING_CONFIRMED, driverPayload);
            }
        } else {
            notificationService.notify(booking.getPassenger(), NotificationType.PAYMENT_FAILED,
                    Map.of("bookingId", booking.getId().toString()));
        }
    }

    /**
     * Activation d un abonnement paye. Renouvellement anticipe (constats F049/F129) : la
     * nouvelle periode demarre a {@code max(now, currentPeriodEnd)} de l abonnement encore
     * actif, qui est clos (EXPIRED) au profit du nouveau - l index unique
     * uq_driver_subscriptions_active n admet qu un ACTIVE par conducteur, et la couverture reste
     * continue puisque le nouvel abonnement est ACTIVE des maintenant avec une echeance
     * posterieure a l ancienne.
     */
    private void handleSubscriptionPaymentResult(DriverSubscription subscription, boolean success) {
        if (success) {
            Instant now = Instant.now();
            Instant start = now;
            Optional<DriverSubscription> current = driverSubscriptionRepository.findActive(subscription.getDriver().getId(), now)
                    .filter(s -> !s.getId().equals(subscription.getId()));
            if (current.isPresent()) {
                DriverSubscription previous = current.get();
                if (previous.getCurrentPeriodEnd() != null && previous.getCurrentPeriodEnd().isAfter(start)) {
                    start = previous.getCurrentPeriodEnd();
                }
                previous.setStatus(SubscriptionStatus.EXPIRED);
                driverSubscriptionRepository.save(previous);
                driverSubscriptionRepository.flush();
            }
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setStartedAt(start);
            subscription.setCurrentPeriodEnd(start.plus(30, ChronoUnit.DAYS));
            driverSubscriptionRepository.save(subscription);
            boolean renewal = current.isPresent();
            notificationService.notifyCritical(subscription.getDriver(), NotificationType.SUBSCRIPTION_ACTIVATED,
                    NotificationTemplates.payload("subscriptionId", subscription.getId().toString(),
                            "startedAt", start.toString(), "currentPeriodEnd", subscription.getCurrentPeriodEnd().toString(),
                            "renewal", renewal),
                    renewal
                            ? "Ekuiseo : votre abonnement conducteur est renouvele jusqu au "
                                    + BookingService.formatLocal(subscription.getCurrentPeriodEnd()) + "."
                            : "Ekuiseo : votre abonnement conducteur est actif, vous ne payez plus de commission ce mois-ci.");
        } else {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            driverSubscriptionRepository.save(subscription);
        }
    }

    /**
     * Remboursement declenche a l annulation (passager ou conducteur, voir BookingService) :
     * delegue a {@link RefundService}, qui marque le paiement a rembourser DANS la transaction
     * d annulation et n appelle Kkiapay qu apres validation (lot 1.2 de l audit, F004/F106).
     * Le montant porte sur booking.depositAmount (regle metier n.21), seul montant encaisse.
     *
     * <p><b>Limitation connue et assumee</b> : l'API Kkiapay confirmee ({@code refund(transactionId)},
     * voir KkiapayGateway) ne documente PAS de remboursement partiel : un remboursement partiel
     * de l'acompte (ex : 50% retenus, regle metier n.7) est marque MANUAL_REQUIRED et traite par
     * le back-office.</p>
     */
    @Transactional
    public RefundOutcome refundBooking(Booking booking, long refundAmountFcfa, String reason) {
        RefundService.RequestOutcome outcome = refundService.requestForBooking(booking, refundAmountFcfa, reason);
        RefundOutcome.Status status = switch (outcome.status()) {
            case REQUESTED -> RefundOutcome.Status.REQUESTED;
            case MANUAL_REQUIRED -> RefundOutcome.Status.MANUAL_REQUIRED;
            case NOT_APPLICABLE -> RefundOutcome.Status.NOT_APPLICABLE;
            case NO_PAYMENT -> RefundOutcome.Status.FAILED;
        };
        return new RefundOutcome(status, outcome.message());
    }

    /** Menage (PaymentHousekeepingScheduler, constat F019) : paiements INITIATED abandonnes -> FAILED. */
    @Transactional
    public int failAbandonedInitiated(Instant before) {
        return paymentRepository.failAbandonedInitiated(before);
    }

    /**
     * Traduit un operateur ou une methode Kkiapay ("MTN", "MTN_MOMO", "MOOV MONEY", "CELTIIS",
     * "CARD", "VISA"...) vers {@link PaymentChannel} ; null si rien ne correspond ("MOBILE_MONEY"
     * generique, "WAVE"...).
     */
    static PaymentChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("MTN")) return PaymentChannel.MTN;
        if (upper.contains("MOOV")) return PaymentChannel.MOOV;
        if (upper.contains("CELTIIS")) return PaymentChannel.CELTIIS;
        if (upper.contains("CARD") || upper.contains("VISA") || upper.contains("MASTERCARD")) return PaymentChannel.CARD;
        try {
            return PaymentChannel.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resultat d'une tentative de remboursement, expose pour audit/notification par l'appelant. */
    public record RefundOutcome(Status status, String message) {
        public enum Status { REQUESTED, FAILED, MANUAL_REQUIRED, NOT_APPLICABLE }

        public boolean refundRequested() {
            return status == Status.REQUESTED;
        }
    }
}
