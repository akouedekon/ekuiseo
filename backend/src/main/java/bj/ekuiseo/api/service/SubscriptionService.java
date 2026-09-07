package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.subscription.SubscriptionResponse;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Abonnement mensuel conducteur (regle metier n.11) : ekuiseo.subscription.price-fcfa
 * (2 000 FCFA par defaut) par mois, en echange d'une commission ramenee a 0% (voir
 * FeePolicy et BookingService#createBooking). Le paiement suit le meme flux Kkiapay
 * que les reservations (widget frontend + verification serveur, voir PaymentService).
 *
 * <p>Constat F206 : « Reprendre le paiement » ne cree plus une souscription par clic.
 * Une souscription PENDING_PAYMENT recente (moins de {@code PENDING_TTL_MINUTES}) est
 * reutilisee avec un nouveau paiement INITIATED ; au-dela elle est annulee
 * (PaymentHousekeepingScheduler). Un paiement de l ancienne souscription confirme plus
 * tard par le webhook active quand meme l abonnement (l argent a ete recu).</p>
 *
 * <p>Phase 2 (constats F049/F129) : le renouvellement est possible a partir de J-7 avant
 * l echeance ; la nouvelle periode demarre alors a la fin de l actuelle (voir
 * PaymentService#handleSubscriptionPaymentResult). Un rappel SUBSCRIPTION_EXPIRING part a
 * J-3, une seule fois ; les abonnements echus passent EXPIRED avec SUBSCRIPTION_EXPIRED
 * (SubscriptionLifecycleScheduler). {@link #getStatus} renvoie l abonnement ACTIVE non echu
 * avant toute souscription en attente.</p>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    static final long PENDING_TTL_MINUTES = 30;
    /** Renouvellement autorise quand l echeance est a moins de cette duree. */
    static final Duration RENEWAL_WINDOW = Duration.ofDays(7);
    /** Rappel envoye quand l echeance est a moins de cette duree. */
    static final Duration EXPIRING_NOTICE = Duration.ofDays(3);

    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final long priceFcfa;

    public SubscriptionService(DriverSubscriptionRepository driverSubscriptionRepository, UserRepository userRepository,
                                PaymentService paymentService, NotificationService notificationService,
                                @Value("${ekuiseo.subscription.price-fcfa:2000}") long priceFcfa) {
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.priceFcfa = priceFcfa;
    }

    /**
     * Demarre (ou reprend) un abonnement PENDING_PAYMENT et renvoie ce qu'il faut pour ouvrir
     * le widget Kkiapay. 409 si un abonnement est actif pour plus de {@link #RENEWAL_WINDOW}.
     */
    @Transactional
    public InitiatePaymentResponse subscribe(UUID driverId) {
        Instant now = Instant.now();
        Optional<DriverSubscription> active = driverSubscriptionRepository.findActive(driverId, now);
        if (active.isPresent() && !isRenewable(active.get(), now)) {
            throw new ConflictException("Un abonnement est deja actif jusqu au "
                    + BookingService.formatLocal(active.get().getCurrentPeriodEnd())
                    + " : le renouvellement est possible 7 jours avant l echeance");
        }
        Optional<DriverSubscription> pending = driverSubscriptionRepository
                .findFirstByDriverIdAndStatusOrderByCreatedAtDesc(driverId, SubscriptionStatus.PENDING_PAYMENT);
        if (pending.isPresent()) {
            DriverSubscription existing = pending.get();
            if (existing.getCreatedAt() != null
                    && existing.getCreatedAt().isAfter(now.minus(PENDING_TTL_MINUTES, ChronoUnit.MINUTES))) {
                return paymentService.initiateSubscriptionPayment(existing);
            }
            existing.setStatus(SubscriptionStatus.CANCELLED);
            driverSubscriptionRepository.save(existing);
        }
        User driver = userRepository.findById(driverId).orElseThrow(() -> new NotFoundException("Conducteur introuvable"));
        DriverSubscription subscription = DriverSubscription.builder()
                .driver(driver)
                .priceFcfa(priceFcfa)
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .build();
        subscription = driverSubscriptionRepository.save(subscription);
        return paymentService.initiateSubscriptionPayment(subscription);
    }

    /** Un abonnement actif se renouvelle des que son echeance est a moins de 7 jours. */
    static boolean isRenewable(DriverSubscription active, Instant now) {
        return active.getCurrentPeriodEnd() == null || !active.getCurrentPeriodEnd().isAfter(now.plus(RENEWAL_WINDOW));
    }

    /** Abonnements jamais payes plus anciens que {@code before} : CANCELLED (PaymentHousekeepingScheduler). */
    @Transactional
    public int expireStalePending(Instant before) {
        List<DriverSubscription> stale = driverSubscriptionRepository
                .findByStatusAndCreatedAtBefore(SubscriptionStatus.PENDING_PAYMENT, before);
        for (DriverSubscription s : stale) {
            s.setStatus(SubscriptionStatus.CANCELLED);
        }
        driverSubscriptionRepository.saveAll(stale);
        return stale.size();
    }

    /**
     * Abonnements ACTIVE dont la periode est echue a {@code now} : EXPIRED, conducteur prevenu
     * (la commission s applique de nouveau). Sans effet sur hasActiveSubscription, qui
     * verifiait deja l echeance ; le statut devient simplement lisible.
     */
    @Transactional
    public int expireEnded(Instant now) {
        List<DriverSubscription> ended = driverSubscriptionRepository
                .findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus.ACTIVE, now);
        for (DriverSubscription s : ended) {
            s.setStatus(SubscriptionStatus.EXPIRED);
            driverSubscriptionRepository.save(s);
            notificationService.notify(s.getDriver(), NotificationType.SUBSCRIPTION_EXPIRED,
                    NotificationTemplates.payload("subscriptionId", s.getId().toString(),
                            "currentPeriodEnd", String.valueOf(s.getCurrentPeriodEnd())));
        }
        if (!ended.isEmpty()) {
            log.info("{} abonnement(s) conducteur echu(s)", ended.size());
        }
        return ended.size();
    }

    /**
     * Rappel J-3 (SUBSCRIPTION_EXPIRING), une seule fois par abonnement : la colonne
     * expiring_notified_at (V16) empeche tout doublon d un jour sur l autre.
     */
    @Transactional
    public int notifyExpiring(Instant now) {
        List<DriverSubscription> soon = driverSubscriptionRepository
                .findByStatusAndCurrentPeriodEndBetweenAndExpiringNotifiedAtIsNull(
                        SubscriptionStatus.ACTIVE, now, now.plus(EXPIRING_NOTICE));
        for (DriverSubscription s : soon) {
            s.setExpiringNotifiedAt(now);
            driverSubscriptionRepository.save(s);
            notificationService.notify(s.getDriver(), NotificationType.SUBSCRIPTION_EXPIRING,
                    NotificationTemplates.payload("subscriptionId", s.getId().toString(),
                            "currentPeriodEnd", String.valueOf(s.getCurrentPeriodEnd())));
        }
        return soon.size();
    }

    /** L abonnement ACTIVE non echu s il existe, sinon le plus recent (PENDING, EXPIRED, CANCELLED). */
    @Transactional(readOnly = true)
    public SubscriptionResponse getStatus(UUID driverId) {
        Instant now = Instant.now();
        DriverSubscription current = driverSubscriptionRepository.findActive(driverId, now)
                .orElseGet(() -> driverSubscriptionRepository.findByDriverIdOrderByCreatedAtDesc(driverId)
                        .stream().findFirst().orElse(null));
        if (current == null) {
            return new SubscriptionResponse(null, priceFcfa, null, false, null, null, false);
        }
        boolean currentlyActive = current.getStatus() == SubscriptionStatus.ACTIVE
                && current.getCurrentPeriodEnd() != null && current.getCurrentPeriodEnd().isAfter(now);
        boolean renewable = !currentlyActive || isRenewable(current, now);
        return new SubscriptionResponse(current.getId(), current.getPriceFcfa(), current.getStatus(), currentlyActive,
                current.getStartedAt(), current.getCurrentPeriodEnd(), renewable);
    }
}
