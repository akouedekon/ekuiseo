package bj.ekuiseo.api.security;

import bj.ekuiseo.api.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Petit utilitaire pour recuperer l'identifiant de l'utilisateur authentifie courant. */
@Component
public class CurrentUser {

    public UUID id() {
        UUID id = idOrNull();
        if (id == null) {
            throw new UnauthorizedException("Utilisateur non authentifie");
        }
        return id;
    }

    /**
     * Variante non-strict : renvoie null plutot que de lever une exception quand
     * personne n'est authentifie. Utilisee par les endpoints partiellement publics
     * (ex : GET /api/v1/trips/{id}, dont la visibilite d'un brouillon depend de
     * savoir si l'appelant est ou non le conducteur, sans que l'authentification
     * soit obligatoire pour consulter un trajet publie).
     */
    public UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof EkuiseoUserDetails principal)) {
            return null;
        }
        return principal.id();
    }
}
