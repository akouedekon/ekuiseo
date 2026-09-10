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
 * Position du vehicule pendant un trajet (suivi en direct, V23). Une ligne par envoi du
 * conducteur ; seule la plus recente sert a l affichage, l historique est purge apres 24 h
 * (RetentionScheduler). Cap, vitesse et precision sont ceux rapportes par le navigateur,
 * absents quand il ne les connait pas.
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

    /** Instant de la mesure cote appareil (borne a l instant de reception). */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
