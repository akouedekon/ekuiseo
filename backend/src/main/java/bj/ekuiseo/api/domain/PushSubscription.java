package bj.ekuiseo.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Abonnement Web Push d un navigateur (V20) : l endpoint fourni par le service push du
 * navigateur (unique) et les deux cles du client (p256dh, auth) qui servent a chiffrer
 * chaque message (RFC 8291). Un utilisateur peut en avoir plusieurs (telephone, ordinateur),
 * bornes a trois par {@code PushSubscriptionService} ; supprimes avec le compte.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "text", unique = true)
    private String endpoint;

    @Column(nullable = false, columnDefinition = "text")
    private String p256dh;

    @Column(nullable = false, columnDefinition = "text")
    private String auth;

    @Column(name = "user_agent", length = 200)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** Dernier envoi accepte par le service push. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Echecs consecutifs d envoi (hors 404/410, qui suppriment l abonnement). */
    @Column(nullable = false)
    @Builder.Default
    private int failures = 0;
}
