package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.subscription.SubscriptionResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constat F206 : reprendre le paiement reutilise la souscription en attente au lieu d en
 * empiler une par clic. Constats F049/F129 (phase 2) : renouvellement des J-7, rappel J-3
 * unique, expiration avec notification, statut = l abonnement actif avant tout PENDING.
 */
class SubscriptionServiceTest {

    private final DriverSubscriptionRepository repository = mock(DriverSubscriptionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final SubscriptionService service = new SubscriptionService(repository, userRepository, paymentService,
            notificationService, 2000);
    private final UUID driverId = UUID.randomUUID();
    private final User driver = User.builder().id(driverId).build();

    @BeforeEach
    void setUp() {
        when(repository.findActive(eq(driverId), any())).thenReturn(Optional.empty());
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

    @Test
    void subscribe_refusesWhileActiveForMoreThanSevenDays_butAllowsRenewalWithinWindow() {
        DriverSubscription active = active(Instant.now().plus(20, ChronoUnit.DAYS));
        when(repository.findActive(eq(driverId), any())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.subscribe(driverId)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("7 jours");
        verify(paymentService, never()).initiateSubscriptionPayment(any());

        active.setCurrentPeriodEnd(Instant.now().plus(5, ChronoUnit.DAYS));
        when(repository.findFirstByDriverIdAndStatusOrderByCreatedAtDesc(driverId, SubscriptionStatus.PENDING_PAYMENT))
                .thenReturn(Optional.empty());

        service.subscribe(driverId);

        verify(paymentService).initiateSubscriptionPayment(any());
        assertThat(active.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE); // l ancien reste actif jusqu au paiement
    }

    @Test
    void expireEnded_marksExpired_andNotifies() {
        DriverSubscription ended = active(Instant.now().minus(1, ChronoUnit.HOURS));
        when(repository.findByStatusAndCurrentPeriodEndBefore(eq(SubscriptionStatus.ACTIVE), any())).thenReturn(List.of(ended));

        assertThat(service.expireEnded(Instant.now())).isEqualTo(1);

        assertThat(ended.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(notificationService).notify(eq(driver), eq(NotificationType.SUBSCRIPTION_EXPIRED), any());
    }

    @Test
    void notifyExpiring_sendsOnce_andStampsTheSubscription() {
        DriverSubscription soon = active(Instant.now().plus(2, ChronoUnit.DAYS));
        when(repository.findByStatusAndCurrentPeriodEndBetweenAndExpiringNotifiedAtIsNull(eq(SubscriptionStatus.ACTIVE), any(), any()))
                .thenReturn(List.of(soon));

        assertThat(service.notifyExpiring(Instant.now())).isEqualTo(1);

        assertThat(soon.getExpiringNotifiedAt()).isNotNull();
        verify(notificationService).notify(eq(driver), eq(NotificationType.SUBSCRIPTION_EXPIRING), any());
        // Le second passage ne trouve plus rien : la colonne expiring_notified_at exclut l abonnement.
        when(repository.findByStatusAndCurrentPeriodEndBetweenAndExpiringNotifiedAtIsNull(eq(SubscriptionStatus.ACTIVE), any(), any()))
                .thenReturn(List.of());
        assertThat(service.notifyExpiring(Instant.now())).isZero();
    }

    @Test
    void getStatus_returnsTheActiveSubscription_beforeAnyPendingOne() {
        DriverSubscription active = active(Instant.now().plus(20, ChronoUnit.DAYS));
        DriverSubscription pending = DriverSubscription.builder().id(UUID.randomUUID()).driver(driver).priceFcfa(2000)
                .status(SubscriptionStatus.PENDING_PAYMENT).build();
        when(repository.findActive(eq(driverId), any())).thenReturn(Optional.of(active));
        when(repository.findByDriverIdOrderByCreatedAtDesc(driverId)).thenReturn(List.of(pending, active));

        SubscriptionResponse status = service.getStatus(driverId);

        assertThat(status.id()).isEqualTo(active.getId());
        assertThat(status.currentlyActive()).isTrue();
        assertThat(status.renewable()).isFalse();
    }

    @Test
    void getStatus_withoutActiveOne_fallsBackToTheMostRecent_andIsRenewable() {
        DriverSubscription expired = active(Instant.now().minus(2, ChronoUnit.DAYS));
        expired.setStatus(SubscriptionStatus.EXPIRED);
        when(repository.findByDriverIdOrderByCreatedAtDesc(driverId)).thenReturn(List.of(expired));

        SubscriptionResponse status = service.getStatus(driverId);

        assertThat(status.id()).isEqualTo(expired.getId());
        assertThat(status.currentlyActive()).isFalse();
        assertThat(status.renewable()).isTrue();
    }

    private DriverSubscription active(Instant periodEnd) {
        return DriverSubscription.builder().id(UUID.randomUUID()).driver(driver).priceFcfa(2000)
                .status(SubscriptionStatus.ACTIVE).startedAt(periodEnd.minus(30, ChronoUnit.DAYS))
                .currentPeriodEnd(periodEnd).build();
    }
}
