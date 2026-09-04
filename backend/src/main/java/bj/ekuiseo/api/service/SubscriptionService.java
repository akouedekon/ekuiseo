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
import java.util.UUID;

/**
 * Abonnement mensuel conducteur (regle metier n.11) : ekuiseo.subscription.price-fcfa
 * (2 000 FCFA par defaut) par mois, en echange d'une commission ramenee a 0% (voir
 * FeePolicy et BookingService#createBooking). Le paiement suit le meme flux Kkiapay
 * que les reservations (widget frontend + verification serveur, voir PaymentService).
 */
@Service
public class SubscriptionService {

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

    /** Demarre un nouvel abonnement (PENDING_PAYMENT) et renvoie ce qu'il faut pour ouvrir le widget Kkiapay. */
    @Transactional
    public InitiatePaymentResponse subscribe(UUID driverId) {
        if (driverSubscriptionRepository.hasActiveSubscription(driverId, Instant.now())) {
            throw new ConflictException("Un abonnement est deja actif pour ce compte");
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
