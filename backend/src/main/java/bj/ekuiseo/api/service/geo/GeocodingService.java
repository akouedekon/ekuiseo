package bj.ekuiseo.api.service.geo;

import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import bj.ekuiseo.api.mapper.GeoPlaceMapper;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Recherche de lieux (villes, quartiers, gares routieres) servant a l'autocompletion des
 * champs origine/destination cote frontend. S'appuie sur la table geo_places (referentiel
 * en base, alimente par les migrations V3 et V17) plutot que sur un service de geocodage
 * externe payant : aucune cle pour un tel service (Google/Mapbox/Nominatim) n'est
 * disponible dans ce projet.
 *
 * <p>Phase 5 (point n.11 de l audit, constats F421/F422) : recherche exacte (nom ou alias,
 * sans casse ni accents) puis repli par similarite de trigrammes quand elle ne renvoie
 * rien ; chaque lieu porte sa ville de rattachement ({@code parentName}) ; le referentiel
 * complet est servi par {@link #listAll()} pour que le front n en garde pas de copie.</p>
 */
@Service
public class GeocodingService {

    private static final int MAX_RESULTS = 15;

    private final GeoPlaceRepository geoPlaceRepository;
    private final GeoPlaceMapper geoPlaceMapper;

    public GeocodingService(GeoPlaceRepository geoPlaceRepository, GeoPlaceMapper geoPlaceMapper) {
        this.geoPlaceRepository = geoPlaceRepository;
        this.geoPlaceMapper = geoPlaceMapper;
    }

    @Transactional(readOnly = true)
    public List<GeoPlaceResponse> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        List<GeoPlace> places = geoPlaceRepository.search(q, MAX_RESULTS);
        if (places.isEmpty()) {
            places = geoPlaceRepository.searchFuzzy(q, MAX_RESULTS);
        }
        return withParents(places);
    }

    /** Referentiel complet, villes d abord (GET /api/v1/geo/places, cache public d un jour). */
    @Transactional(readOnly = true)
    public List<GeoPlaceResponse> listAll() {
        return withParents(geoPlaceRepository.findAllByOrderByKindAscNameAsc());
    }

    /** Noms des villes de rattachement resolus en une requete pour toute la liste (jamais un appel par lieu). */
    private List<GeoPlaceResponse> withParents(List<GeoPlace> places) {
        Map<UUID, String> names = new HashMap<>();
        for (GeoPlace place : places) {
            names.put(place.getId(), place.getName());
        }
        List<UUID> missing = places.stream().map(GeoPlace::getParentPlaceId).filter(Objects::nonNull)
                .filter(id -> !names.containsKey(id)).distinct().toList();
        if (!missing.isEmpty()) {
            for (GeoPlace parent : geoPlaceRepository.findAllById(missing)) {
                names.put(parent.getId(), parent.getName());
            }
        }
        return places.stream()
                .map(p -> geoPlaceMapper.toResponse(p, p.getParentPlaceId() == null ? null : names.get(p.getParentPlaceId())))
                .toList();
    }
}
