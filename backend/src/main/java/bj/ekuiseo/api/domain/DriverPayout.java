package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.PayoutStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "driver_payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverPayout {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PayoutStatus status = PayoutStatus.PENDING;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "destination_msisdn", nullable = false, length = 20)
    private String destinationMsisdn;

    /** Periode couverte par ce lot de reversement hebdomadaire (regle metier n.12). */
    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;
}
