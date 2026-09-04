package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.PaymentChannel;
import bj.ekuiseo.api.domain.enums.PaymentProvider;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    /** Nullable : un paiement peut regler une reservation (booking) OU un abonnement
     * conducteur (subscription), jamais les deux (voir contrainte chk_payments_target,
     * migration V4). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private DriverSubscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PaymentProvider provider = PaymentProvider.KKIAPAY;

    @Column(name = "provider_tx_id", nullable = false)
    private String providerTxId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    @Builder.Default
    private long fee = 0L;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.INITIATED;

    /** Mappage JSON natif Hibernate 6 (aucune dependance externe requise). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
