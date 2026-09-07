package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import bj.ekuiseo.api.service.geo.GeocodingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Referentiel de lieux (villes, quartiers, gares du Benin, migrations V3 et V17) pour l'autocompletion origine/destination. */
@Tag(name = "Geocodage", description = "Autocompletion de villes/quartiers/gares pour les champs origine/destination")
@RestController
@RequestMapping("/api/v1/geo")
public class GeoController {

    private final GeocodingService geocodingService;

    public GeoController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    /**
     * Referentiel quasi statique (table geo_places alimentee par migration) : cache public
     * d un jour cote navigateur et proxy (constat F416), en plus de la limitation de debit par
     * IP de RateLimitingFilter sur ces endpoints publics.
     */
    @Operation(summary = "Rechercher un lieu", description = "Recherche insensible a la casse et aux accents sur le nom ou un alias (prefixe ou sous-chaine), avec repli tolerant aux fautes (trigrammes) quand rien ne correspond : q=cotonou, q=calavi ou q=natitngou. Chaque lieu porte sa ville de rattachement (parentName). Reponse cacheable 24 h (Cache-Control: public, max-age=86400).")
    @GetMapping("/search")
    public ResponseEntity<List<GeoPlaceResponse>> search(@RequestParam @Size(max = 100) String q) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(geocodingService.search(q));
    }

    @Operation(summary = "Referentiel complet des lieux", description = "Toutes les villes, quartiers et gares du referentiel (kind CITY/DISTRICT/STATION), villes d abord : source unique pour le front, qui n en garde plus de copie. Reponse cacheable 24 h (Cache-Control: public, max-age=86400).")
    @GetMapping("/places")
    public ResponseEntity<List<GeoPlaceResponse>> places() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(geocodingService.listAll());
    }
}
