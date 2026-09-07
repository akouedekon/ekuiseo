package bj.ekuiseo.api.domain.enums;

/**
 * Nature d un lieu du referentiel geo_places (V3, V17) : ville, quartier ou arrondissement
 * rattache a une ville, gare routiere ou carrefour servant de point de rendez-vous.
 */
public enum GeoPlaceKind {
    CITY,
    DISTRICT,
    STATION
}
