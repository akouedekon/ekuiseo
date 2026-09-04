package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.ComfortLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(length = 50)
    private String color;

    @Column(nullable = false, length = 20)
    private String plate;

    @Column(nullable = false)
    private int seats;

    @Enumerated(EnumType.STRING)
    @Column(name = "comfort_level", nullable = false, length = 20)
    @Builder.Default
    private ComfortLevel comfortLevel = ComfortLevel.BASIC;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
