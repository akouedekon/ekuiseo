package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Abonnement mensuel conducteur (regle metier n.11) : 2 000 FCFA/mois, en
 * echange de quoi la commission de service (voir {@link bj.ekuiseo.api.common.FeePolicy})
 * est ramenee a 0% sur les reservations de ses trajets pendant la periode active.
 */
@Entity
@Table(name = "driver_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverSubscription {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @Column(name = "price_fcfa", nullable = false)
    private long priceFcfa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.PENDING_PAYMENT;

    @Column(name = "started_at")
    private Instant startedAt;

    /** Fin de periode couverte ; l'abonnement n'est considere actif que si status=ACTIVE ET currentPeriodEnd est dans le futur. */
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
