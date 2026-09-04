package bj.ekuiseo.api.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripStop {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "planned_at")
    private Instant plannedAt;

    @Column(name = "price_from_origin", nullable = false)
    @Builder.Default
    private long priceFromOrigin = 0L;
}
