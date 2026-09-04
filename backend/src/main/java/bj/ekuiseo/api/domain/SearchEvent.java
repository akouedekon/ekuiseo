package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.TripType;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Trace d'une recherche de trajets (migration V9) : ce que le passager a demande
 * et combien de trajets il a obtenus. C'est la matiere premiere des indicateurs de
 * liquidite du back-office (taux de recherche aboutie, recherche -> reservation,
 * axes en penurie), voir {@code AdminLiquidityService}.
 *
 * <p>Volontairement sans relation JPA : {@code userId}, {@code originPlaceId} et
 * {@code destPlaceId} sont de simples UUID. Cette entite est ecrite en masse et
 * en asynchrone ({@code SearchEventService#record}), et lue uniquement par des
 * requetes natives agregees - elle n'a jamais besoin de naviguer vers un
 * utilisateur ou un lieu.</p>
 */
@Entity
@Table(name = "search_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchEvent {

    @Id
    @GeneratedValue
    private UUID id;

    /** Utilisateur connecte au moment de la recherche ; null pour une recherche anonyme. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "origin_label")
    private String originLabel;

    @Column(name = "origin_lat", nullable = false)
    private double originLat;

    @Column(name = "origin_lng", nullable = false)
    private double originLng;

    /** Ville du referentiel geo_places la plus proche de l'origine (cle de regroupement des axes). */
    @Column(name = "origin_place_id")
    private UUID originPlaceId;

    @Column(name = "dest_label")
    private String destLabel;

    @Column(name = "dest_lat", nullable = false)
    private double destLat;

    @Column(name = "dest_lng", nullable = false)
    private double destLng;

    @Column(name = "dest_place_id")
    private UUID destPlaceId;

    /** Jour demande ; null = toutes dates. */
    @Column(name = "requested_date")
    private LocalDate requestedDate;

    @Column(nullable = false)
    @Builder.Default
    private int seats = 1;

    /** Type de trajet demande ; null = les deux. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", length = 20)
    private TripType tripType;

    @Column(name = "radius_km", nullable = false)
    private double radiusKm;

    /** Nombre total de trajets renvoyes (toutes pages confondues). 0 = recherche infructueuse. */
    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
