package bj.ekuiseo.api.domain.enums;

/** Niveau de confiance d un conducteur (contrat A.9) : identite verifiee, puis EXPERIENCED a partir de 10 trajets termines comme conducteur avec une note >= 4,5 sur au moins 5 avis (voir TrustPolicy). */
public enum TrustLevel {
    UNVERIFIED,
    VERIFIED,
    EXPERIENCED
}
