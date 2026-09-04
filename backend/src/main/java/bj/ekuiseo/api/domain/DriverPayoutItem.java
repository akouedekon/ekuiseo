package bj.ekuiseo.api.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Ligne de detail d'un reversement (regle metier n.12) : associe une reservation
 * payee (MoMo, encaissee par la plateforme) au lot de reversement qui la solde,
 * afin qu'une meme reservation ne soit jamais reversee deux fois (voir
 * contrainte unique uq_driver_payout_items_booking, migration V5).
 */
@Entity
@Table(name = "driver_payout_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverPayoutItem {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id", nullable = false)
    private DriverPayout payout;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;
}
