package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.monitoring.ClientErrorReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collecteur des erreurs du navigateur (constat F440). Le front envoie chaque erreur
 * (ecran blanc, promesse rejetee, echec d API 5xx) en JSON via sendBeacon ; elle est
 * ecrite dans le journal du backend sous le logger {@code bj.ekuiseo.api.client}, sur une
 * seule ligne, avec l identifiant de requete et l utilisateur du jeton s il y en a un :
 * {@code docker logs ekuiseo-backend | grep bj.ekuiseo.api.client} suffit a l exploitation.
 *
 * <p>Public (le rapport doit partir meme quand la session est perdue), borne par
 * {@code RateLimitingFilter} (quota {@code errors:} par IP) et par la validation du corps.
 * Aucune donnee n est stockee en base ni renvoyee : 202 quoi qu il arrive.</p>
 */
@Tag(name = "Supervision", description = "Collecte des erreurs du navigateur (public, limite par IP)")
@RestController
@RequestMapping("/api/v1/client-errors")
public class ClientErrorController {

    /** Logger dedie : un filtre grep sur son nom isole les erreurs du front dans le journal. */
    private static final Logger log = LoggerFactory.getLogger("bj.ekuiseo.api.client");

    @Operation(summary = "Signale une erreur survenue dans le navigateur",
            description = "Journalisee cote serveur (une ligne, sans donnee personnelle). 202 des que le corps est valide.")
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@Valid @RequestBody ClientErrorReport report) {
        log.warn("[{}] {} | route={} | statut={} | version={} | ua={} | a={} | pile={} | composants={}",
                oneLine(report.source(), 100),
                oneLine(report.message(), 1000),
                oneLine(report.route(), 300),
                report.status(),
                oneLine(report.version(), 50),
                oneLine(report.userAgent(), 400),
                oneLine(report.occurredAt(), 40),
                oneLine(report.stack(), 4000),
                oneLine(report.componentStack(), 4000));
    }

    /** Une seule ligne de journal par rapport : retours a la ligne et caracteres de controle aplatis. */
    public static String oneLine(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String flat = value.replaceAll("[\\r\\n]+", " ↵ ").replaceAll("[\\p{Cntrl}]", " ").trim();
        // Le jeton du lien public de suivi (/live/{token}) donne la position d un vehicule : jamais en clair dans un journal.
        flat = flat.replaceAll("(/live/)[A-Za-z0-9_-]{8,}", "$1***");
        return flat.length() > max ? flat.substring(0, max) : flat;
    }
}
