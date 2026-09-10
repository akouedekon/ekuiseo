package bj.ekuiseo.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Cle d idempotence d une ecriture financiere (V26, contrat A.1) : la reponse d une requete
 * portant l en-tete {@code Idempotency-Key} est memorisee pour (cle, utilisateur, route) et
 * rejouee telle quelle si le client renvoie la meme requete (meme corps). Un corps different
 * sous la meme cle est refuse (422). Conservee 24 h.
 *
 * <p>{@code responseStatus} est NULL tant que la premiere requete est en cours : un second
 * appel simultane recoit 409 plutot que de declencher deux fois l ecriture.</p>
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Methode et chemin concret de la requete (« POST /api/v1/bookings/{uuid}/cancel »). */
    @Column(nullable = false, length = 200)
    private String route;

    /** SHA-256 hexadecimal du corps de la requete (chaine vide hachee si aucun corps). */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** Corps JSON de la reponse memorisee, tel quel. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
