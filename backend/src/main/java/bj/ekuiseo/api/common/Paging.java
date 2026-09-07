package bj.ekuiseo.api.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pagination serveur des listes du back-office (constat F237) : page indexee a 0, taille
 * bornee a {@link #MAX_PAGE_SIZE} quoi que demande le client, tri impose par le serveur
 * (jamais lu dans la requete : un tri libre sur une colonne non indexee est une porte
 * ouverte a une requete lente).
 */
public final class Paging {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private Paging() {
    }

    public static Pageable of(int page, int size) {
        return PageRequest.of(Math.max(0, page), clamp(size));
    }

    public static Pageable of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(0, page), clamp(size), sort);
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }
}
