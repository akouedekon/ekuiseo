package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "gender_pref_note")
    private String genderPrefNote;

    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    @Column(name = "identity_verified", nullable = false)
    @Builder.Default
    private boolean identityVerified = false;

    @Column(name = "rating_avg", nullable = false)
    @Builder.Default
    private java.math.BigDecimal ratingAvg = java.math.BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    @Builder.Default
    private int ratingCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /** Role applicatif (USER par defaut). Seul ADMIN donne acces a /api/v1/admin/**. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    /** Nombre d'annulations tardives (regle metier n.9) constatees en tant que conducteur. */
    @Column(name = "late_cancellations_count", nullable = false)
    @Builder.Default
    private int lateCancellationsCount = 0;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_reason", columnDefinition = "text")
    private String suspendedReason;

    /**
     * Abonnement Web Push du navigateur (endpoint + cles), au format standard
     * PushSubscriptionJSON. Colonne preparee pour une future implementation Web
     * Push (voir README "Ce qui reste a faire") ; non exploitee pour l'instant.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "push_subscription", columnDefinition = "jsonb")
    private Map<String, Object> pushSubscription;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
