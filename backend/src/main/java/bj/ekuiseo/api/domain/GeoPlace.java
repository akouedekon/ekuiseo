package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.GeoPlaceKind;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Lieu geocode (ville, quartier ou gare routiere), servant de referentiel en base pour
 * la recherche d'adresses cote application (voir GeocodingService). Alimente par les
 * migrations V3 et V17 (villes et quartiers du Benin, gares et carrefours de rendez-vous,
 * Lome/Lagos pour le trafic transfrontalier).
 *
 * <p>La colonne {@code aliases} (text[], V17 : autres graphies, deja normalisees) n est
 * pas mappee ici : elle n est lue que par les requetes natives de recherche
 * (GeoPlaceRepository#search), jamais exposee telle quelle.</p>
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

    /** CITY, DISTRICT (quartier rattache a une ville) ou STATION (gare, carrefour), voir V17. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GeoPlaceKind kind;

    @Column(name = "parent_place_id")
    private UUID parentPlaceId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;
}
