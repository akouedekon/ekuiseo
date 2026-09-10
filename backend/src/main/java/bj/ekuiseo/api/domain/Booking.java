package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NoShowResolution;
import bj.ekuiseo.api.domain.enums.PassengerConfirmation;
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

    /** Echeance de la reponse du conducteur pour une reservation PENDING_DRIVER_APPROVAL (V19) ; null sinon. */
    @Column(name = "approval_deadline_at")
    private Instant approvalDeadlineAt;

    /** Annulation gratuite ouverte jusqu a cet instant (V13) quand le conducteur a modifie l horaire d un trajet reserve. */
    @Column(name = "free_cancellation_until")
    private Instant freeCancellationUntil;

    /** Constat du passager apres le depart (V21) : tacite (PENDING) passe 24 h, trajet effectue, ou conducteur absent. */
    @Enumerated(EnumType.STRING)
    @Column(name = "passenger_confirmation", nullable = false, length = 20)
    @Builder.Default
    private PassengerConfirmation passengerConfirmation = PassengerConfirmation.PENDING;

    @Column(name = "passenger_confirmed_at")
    private Instant passengerConfirmedAt;

    /** Conducteur declare absent (V25) : echeance du remboursement automatique de l acompte (declaration + fenetre de contestation). */
    @Column(name = "driver_no_show_refund_due_at")
    private Instant driverNoShowRefundDueAt;

    /** Le conducteur conteste l absence : le remboursement automatique est gele jusqu a la decision de la moderation. */
    @Column(name = "driver_no_show_contested_at")
    private Instant driverNoShowContestedAt;

    @Column(name = "driver_no_show_contest_details", columnDefinition = "text")
    private String driverNoShowContestDetails;

    /** Issue de la declaration : remboursement du passager (automatique ou decide) ou trajet maintenu au profit du conducteur. */
    @Enumerated(EnumType.STRING)
    @Column(name = "driver_no_show_resolution", length = 20)
    private NoShowResolution driverNoShowResolution;

    @Column(name = "driver_no_show_resolved_at")
    private Instant driverNoShowResolvedAt;

    /** Administrateur ayant tranche ; null pour le remboursement automatique a l echeance. */
    @Column(name = "driver_no_show_resolved_by")
    private UUID driverNoShowResolvedBy;

    /** Suivi en direct (V28) : notification « conducteur a moins de 1 km » envoyee au passager, une seule fois. */
    @Column(name = "driver_nearby_notified_at")
    private Instant driverNearbyNotifiedAt;

    /** Suivi en direct (V28) : notification « conducteur arrive » (moins de 150 m) envoyee au passager, une seule fois. */
    @Column(name = "driver_arrived_notified_at")
    private Instant driverArrivedNotifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
