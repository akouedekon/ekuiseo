package bj.ekuiseo.api.common;

import java.time.ZoneId;

/**
 * Fuseau de reference du produit : le Benin est en UTC+1 toute l annee (pas d heure
 * d ete). Tout calcul de « jour civil », de jour de semaine ou d heure locale
 * (recherche par date, occurrences recurrentes, trajet de la semaine, rappels,
 * series du tableau de bord) passe par cette constante, jamais par UTC
 * (constat F415 de l audit : un depart a 00:30 a Cotonou tombait la veille).
 */
public final class Tz {

    public static final ZoneId BENIN = ZoneId.of("Africa/Porto-Novo");

    private Tz() {
    }
}
