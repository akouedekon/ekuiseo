package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.subscription.SubscriptionResponse;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class SubscriptionService {

    static final long PENDING_TTL_MINUTES = 30;

    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final long priceFcfa;

    public SubscriptionService(DriverSubscriptionRepository driverSubscriptionRepository, UserRepository userRepository,
                                PaymentService paymentService,
                                @Value("${ekuiseo.subscription.price-fcfa:2000}") long priceFcfa) {
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.priceFcfa = priceFcfa;
    }

    /** Demarre (ou reprend) un abonnement PENDING_PAYMENT et renvoie ce qu'il faut pour ouvrir le widget Kkiapay. */
    @Transactional
    public InitiatePaymentResponse subscribe(UUID driverId) {
        Instant now = Instant.now();
        if (driverSubscriptionRepository.hasActiveSubscription(driverId, now)) {
            throw new ConflictException("Un abonnement est deja actif pour ce compte");
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

    @Transactional(readOnly = true)
    public SubscriptionResponse getStatus(UUID driverId) {
        DriverSubscription latest = driverSubscriptionRepository.findByDriverIdOrderByCreatedAtDesc(driverId)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            return new SubscriptionResponse(null, priceFcfa, null, false, null, null);
        }
        boolean currentlyActive = latest.getStatus() == SubscriptionStatus.ACTIVE
                && latest.getCurrentPeriodEnd() != null && latest.getCurrentPeriodEnd().isAfter(Instant.now());
        return new SubscriptionResponse(latest.getId(), latest.getPriceFcfa(), latest.getStatus(), currentlyActive,
                latest.getStartedAt(), latest.getCurrentPeriodEnd());
    }
}
