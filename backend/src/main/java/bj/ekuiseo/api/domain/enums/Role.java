package bj.ekuiseo.api.domain.enums;

/** Role applicatif d'un utilisateur. Tout utilisateur peut etre conducteur et/ou
 * passager (ce n'est pas un role au sens securite) ; seul ADMIN distingue les
 * comptes du back-office, qui seuls peuvent acceder a /api/v1/admin/**. */
public enum Role {
    USER,
    ADMIN
}
