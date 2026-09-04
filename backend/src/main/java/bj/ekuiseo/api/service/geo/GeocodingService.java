package bj.ekuiseo.api.service.geo;

import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import bj.ekuiseo.api.mapper.GeoPlaceMapper;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recherche de lieux (villes, quartiers) servant a l'autocompletion des champs
 * origine/destination cote frontend. S'appuie sur la table geo_places (cache en
 * base, alimentee par la migration V3) plutot que sur un service de geocodage
 * externe payant : aucune cle pour un tel service (Google/Mapbox/Nominatim) n'est
 * disponible dans ce projet.
 *
 * <p>Evolution possible (non implementee ici, voir README) : en cas de recherche
 * infructueuse dans geo_places, appeler un service de geocodage externe (ex :
 * Nominatim/OpenStreetMap) et mettre son resultat en cache dans cette meme table
 * pour les recherches futures.</p>
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
        return geoPlaceRepository.search(query.trim(), MAX_RESULTS).stream()
                .map(geoPlaceMapper::toResponse)
                .toList();
    }
}
