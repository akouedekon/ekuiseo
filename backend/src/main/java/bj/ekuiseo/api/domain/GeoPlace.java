package bj.ekuiseo.api.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Lieu geocode (ville ou quartier), servant de cache/referentiel en base pour
 * la recherche d'adresses cote application (voir GeocodingService). Alimente
 * par la migration V3 (villes et quartiers du Benin + Lome/Lagos pour le
 * trafic transfrontalier).
 */
@Entity
@Table(name = "geo_places")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeoPlace {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Nom en minuscules et sans accents, precalcule pour une recherche rapide et insensible aux accents. */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(length = 100)
    private String region;

    @Column(name = "country_code", nullable = false, length = 2)
    @Builder.Default
    private String countryCode = "BJ";

    /** CITY ou DISTRICT (quartier rattache a une ville, ex: Cadjehoun -> Cotonou). */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "parent_place_id")
    private UUID parentPlaceId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;
}
