package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.LiveRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Position d un participant pendant un trajet (suivi en direct, V23 ; par participant
 * depuis V28). Une ligne par ecriture retenue (LocationUpdateService n ecrit qu une
 * position toutes les 30 s ou tous les 200 m par participant, plus chaque position
 * signalee par un flag) ; seule la plus recente sert a l affichage, l historique est
 * purge apres 24 h (RetentionScheduler). Cap, vitesse et precision sont ceux rapportes
 * par l appareil, absents quand il ne les connait pas.
 */
@Entity
@Table(name = "trip_positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPosition {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Participant qui a envoye la position (conducteur ou passager confirme), V28. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private LiveRole role = LiveRole.DRIVER;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    /** Cap en degres (0 = nord, sens horaire), null si inconnu. */
    private Float heading;

    @Column(name = "speed_kmh")
    private Float speedKmh;

    @Column(name = "accuracy_m")
    private Float accuracyM;

    /** Instant de la mesure cote appareil (ramene a l heure serveur en cas de derive, V28). */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Anomalies relevees a la reception, separees par des virgules ; null = aucune (V28). */
    @Column(length = 200)
    private String flags;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
