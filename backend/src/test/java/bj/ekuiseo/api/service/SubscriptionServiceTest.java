package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F206 : reprendre le paiement reutilise la souscription en attente au lieu d en empiler une par clic. */
class SubscriptionServiceTest {

    private final DriverSubscriptionRepository repository = mock(DriverSubscriptionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final SubscriptionService service = new SubscriptionService(repository, userRepository, paymentService, 2000);
    private final UUID driverId = UUID.randomUUID();
    private final User driver = User.builder().id(driverId).build();

    @BeforeEach
    void setUp() {
        when(repository.hasActiveSubscription(eq(driverId), any())).thenReturn(false);
        when(userRepository.findById(driverId)).thenReturn(Optional.of(driver));
        when(repository.save(any(DriverSubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.initiateSubscriptionPayment(any())).thenAnswer(inv -> {
            DriverSubscription s = inv.getArgument(0);
            return new InitiatePaymentResponse(UUID.randomUUID(), "ref", 2000, "pk", true, Map.of("subscriptionId", String.valueOf(s.getId())));
        });
    }

    @Test
    void reusesARecentPendingSubscription() {
        DriverSubscription pending = DriverSubscription.builder().id(UUID.randomUUID()).driver(driver).priceFcfa(2000)
                .status(SubscriptionStatus.PENDING_PAYMENT).build();
        pending.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(repository.findFirstByDriverIdAndStatusOrderByCreatedAtDesc(driverId, SubscriptionStatus.PENDING_PAYMENT))
                .thenReturn(Optional.of(pending));

        InitiatePaymentResponse res = service.subscribe(driverId);

        assertThat(res.widgetData().get("subscriptionId")).isEqualTo(pending.getId().toString());
        verify(repository, never()).save(any());
        verify(paymentService).initiateSubscriptionPayment(pending);
    }

    @Test
    void cancelsAStalePendingSubscription_andCreatesANewOne() {
        DriverSubscription stale = DriverSubscription.builder().id(UUID.randomUUID()).driver(driver).priceFcfa(2000)
                .status(SubscriptionStatus.PENDING_PAYMENT).build();
        stale.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        when(repository.findFirstByDriverIdAndStatusOrderByCreatedAtDesc(driverId, SubscriptionStatus.PENDING_PAYMENT))
                .thenReturn(Optional.of(stale));

        service.subscribe(driverId);

        assertThat(stale.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        verify(paymentService).initiateSubscriptionPayment(any());
        verify(paymentService, never()).initiateSubscriptionPayment(stale);
    }

    @Test
    void expireStalePending_cancelsOldOnes() {
        DriverSubscription a = DriverSubscription.builder().id(UUID.randomUUID()).driver(driver).status(SubscriptionStatus.PENDING_PAYMENT).build();
        when(repository.findByStatusAndCreatedAtBefore(eq(SubscriptionStatus.PENDING_PAYMENT), any())).thenReturn(List.of(a));

        assertThat(service.expireStalePending(Instant.now())).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }
}
