package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.LedgerAccount;
import bj.ekuiseo.api.domain.enums.LedgerDirection;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Ecriture du registre financier (V26, contrat A.4). <b>Ajout seul</b> : aucun setter, aucune
 * mise a jour ni suppression (un trigger en base les refuse) ; une erreur se corrige par une
 * ecriture {@code ADJUSTMENT} decrite. Les identifiants lies sont des UUID nus (pas de
 * navigation JPA) : le registre survit a tout ce qu il decrit.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private LedgerDirection direction;

    @Column(name = "amount_fcfa", nullable = false)
    private long amountFcfa;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "XOF";

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "payout_id")
    private UUID payoutId;

    /** Utilisateur concerne : le passager qui paie, le conducteur credite ou reverse. */
    @Column(name = "user_id")
    private UUID userId;

    /** KKIAPAY, CASH, ou l operateur mobile money d un reversement. */
    @Column(length = 20)
    private String provider;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(length = 500)
    private String description;

    /** Administrateur auteur d une correction ; null = ecriture du systeme. */
    @Column(name = "created_by")
    private UUID createdBy;
}
