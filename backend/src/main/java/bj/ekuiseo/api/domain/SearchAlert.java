package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "search_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchAlert {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "origin_label", nullable = false)
    private String originLabel;

    @Column(name = "origin_lat", nullable = false)
    private double originLat;

    @Column(name = "origin_lng", nullable = false)
    private double originLng;

    @Column(name = "dest_label", nullable = false)
    private String destLabel;

    @Column(name = "dest_lat", nullable = false)
    private double destLat;

    @Column(name = "dest_lng", nullable = false)
    private double destLng;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    /** Nombre de places recherchees (regle metier n.13, migration V6). */
    @Column(nullable = false)
    @Builder.Default
    private int seats = 1;

    /** Type de trajet recherche ; null = les deux types (migration V6). */
    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", length = 20)
    private TripType tripType;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
