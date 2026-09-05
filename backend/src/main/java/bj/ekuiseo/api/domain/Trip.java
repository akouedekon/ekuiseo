package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Un trajet propose par un conducteur (interurbain planifie ou quotidien recurrent).
 * Les colonnes geography (origin_point / dest_point) sont calculees cote base par un
 * trigger PostgreSQL a partir de origin_lat/origin_lng et dest_lat/dest_lng ; elles ne
 * sont donc pas mappees ici et ne sont utilisees que par les requetes natives de
 * recherche geospatiale (voir TripRepository#search).
 */
@Entity
@Table(name = "trips")
// Ne reecrit que les colonnes modifiees : un PATCH du prix ne doit pas ecraser seats_available
// avec une valeur perimee (constat F005).
@org.hibernate.annotations.DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(name = "trip_type", nullable = false, length = 20)
    private TripType tripType;

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

    @Column(name = "departure_at", nullable = false)
    private Instant departureAt;

    @Column(name = "seats_total", nullable = false)
    private int seatsTotal;

    @Column(name = "seats_available", nullable = false)
    private int seatsAvailable;

    @Column(name = "price_per_seat", nullable = false)
    private long pricePerSeat;

    @Column(name = "instant_booking", nullable = false)
    @Builder.Default
    private boolean instantBooking = true;

    @Column(name = "luggage_policy")
    private String luggagePolicy;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TripStatus status = TripStatus.DRAFT;

    /** Regle de recurrence au format RRULE (RFC 5545), utilisee pour les trajets QUOTIDIEN. */
    @Column(name = "recurrence_rule")
    private String recurrenceRule;

    @Column(name = "parent_trip_id")
    private UUID parentTripId;

    /** Horodatage d'envoi du rappel "la veille du depart" (regle metier n.10), pour eviter les doublons. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
