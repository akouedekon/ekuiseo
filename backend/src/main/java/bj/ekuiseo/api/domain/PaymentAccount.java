package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
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
 * Compte mobile money enregistre par un utilisateur (regle metier n.18),
 * expose cote API sous le nom "moyen de paiement" (table {@code payment_methods}).
 * Nomme {@code PaymentAccount} cote Java pour eviter toute confusion avec
 * l'enum {@link bj.ekuiseo.api.domain.enums.PaymentMethod} (MOMO/CASH, le mode
 * de reglement d'UNE reservation) qui designe un concept different.
 */
@Entity
@Table(name = "payment_methods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MobileMoneyOperator provider;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 100)
    private String label;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
