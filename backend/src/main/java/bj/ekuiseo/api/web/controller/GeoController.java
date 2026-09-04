package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import bj.ekuiseo.api.service.geo.GeocodingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Recherche de lieux (villes/quartiers du Benin, voir migration V3) pour l'autocompletion origine/destination. */
@Tag(name = "Geocodage", description = "Autocompletion de villes/quartiers pour les champs origine/destination")
@RestController
@RequestMapping("/api/v1/geo")
public class GeoController {

    private final GeocodingService geocodingService;

    public GeoController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @Operation(summary = "Rechercher un lieu", description = "Recherche insensible a la casse et aux accents sur le nom (prefixe ou sous-chaine), ex: q=cotonou ou q=natitngou.")
    @GetMapping("/search")
    public List<GeoPlaceResponse> search(@RequestParam String q) {
        return geocodingService.search(q);
    }
}
