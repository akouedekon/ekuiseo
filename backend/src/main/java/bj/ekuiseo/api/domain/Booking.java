package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private User passenger;

    @Column(nullable = false)
    private int seats;

    @Column(name = "pickup_stop_id")
    private UUID pickupStopId;

    @Column(name = "dropoff_stop_id")
    private UUID dropoffStopId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "service_fee", nullable = false)
    private long serviceFee;

    /**
     * Part de {@code amount} prelevee en ligne des la reservation (regle metier
     * n.21, migration V7) : {@code amount} en MOMO_FULL, un acompte calcule par
     * FeePolicy#computeDepositAmount en MOMO_DEPOSIT, 0 en CASH.
     */
    @Column(name = "deposit_amount", nullable = false)
    private long depositAmount;

    /** {@code amount - depositAmount}, regle en especes au conducteur pendant le trajet. */
    @Column(name = "balance_due_on_board", nullable = false)
    private long balanceDueOnBoard;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    @Builder.Default
    private PaymentMethod paymentMethod = PaymentMethod.MOMO_DEPOSIT;

    /** Echeance de l acompte pour une reservation PENDING_PAYMENT (V12) ; null en especes ou une fois confirmee. Prolongee a l initiation du paiement. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
