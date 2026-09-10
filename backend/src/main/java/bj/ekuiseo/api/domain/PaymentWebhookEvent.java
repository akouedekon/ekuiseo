package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.WebhookOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Map;
import java.util.UUID;

/**
 * Webhook recu de l agregateur (V26, contrat A.5), persiste AVANT tout traitement avec le hash
 * SHA-256 de son corps. Un corps deja traite (PROCESSED / IGNORED) qui revient est enregistre
 * {@code DUPLICATE} et jamais retraite ; un corps revenu apres un ERROR (verification non
 * conclusive) ou un REJECTED reprend la meme ligne.
 */
@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_tx_id", length = 100)
    private String providerTxId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @CreationTimestamp
    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private WebhookOutcome outcome = WebhookOutcome.RECEIVED;

    @Column(length = 500)
    private String error;
}
