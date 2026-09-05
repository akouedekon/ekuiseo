package bj.ekuiseo.api.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Code a usage unique envoye par e-mail (ou SMS) pour ouvrir une session ; le numero de telephone reste l identifiant du compte. */
@Entity
@Table(name = "otp_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String purpose = "REGISTER";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Nombre de tentatives de verification incorrectes (regle metier n.8 : code
     * invalide au-dela d'un nombre maximal d'essais, voir OtpService). */
    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Canal par lequel ce code est parti : EMAIL ou SMS (V10). Determine le drapeau
     * pose a la verification (email_verified ou phone_verified). */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String channel = "SMS";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
