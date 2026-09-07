package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.TripRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Apercu de partage d un trajet (constat F341) : Caddy dirige les robots des messageries
 * (WhatsApp, Facebook, Twitter, Telegram, LinkedIn) qui demandent /trips/{id} vers
 * GET /share/trips/{id}, hors /api et public (SecurityConfig). La page renvoyee est un HTML
 * minimal portant les balises Open Graph du trajet, et redirige tout navigateur humain vers
 * l application (meta refresh). Un trajet absent, brouillon ou modele de navette (jamais
 * reservable) renvoie un 404 HTML sobre sans rien reveler. Tout texte est echappe.
 */
@Tag(name = "Partage", description = "Apercu Open Graph d un trajet pour les messageries (public)")
@RestController
@RequestMapping("/share")
public class ShareController {

    static final String PUBLIC_BASE_URL = "https://ekuiseo.com";
    /** Fleche typographique (U+2192) du titre « Cotonou -> Bohicon », ecrite en echappement pour rester en ASCII. */
    static final String ARROW = String.valueOf((char) 0x2192);
    /** « sam. 12 sept. 07:30 », heure du Benin. */
    private static final DateTimeFormatter SHORT_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE d MMM HH:mm", Locale.FRENCH).withZone(Tz.BENIN);

    private final TripRepository tripRepository;

    public ShareController(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Operation(summary = "Apercu Open Graph d un trajet", description = "HTML minimal (og:title, og:description, og:url, og:image, twitter:card) avec redirection immediate vers /trips/{id}. 404 HTML si le trajet est absent, brouillon ou modele.")
    @GetMapping(value = "/trips/{id}", produces = "text/html;charset=UTF-8")
    @Transactional(readOnly = true)
    public ResponseEntity<String> trip(@PathVariable UUID id) {
        Trip trip = tripRepository.findById(id).orElse(null);
        if (trip == null || trip.getStatus() == TripStatus.DRAFT || trip.getStatus() == TripStatus.TEMPLATE) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                    .body(notFoundHtml());
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(render(trip));
    }

    /** Titre de l apercu : « Cotonou -> Bohicon, sam. 12 sept. 07:30 » (fleche typographique dans le HTML). */
    static String title(Trip trip) {
        String when = trip.getDepartureAt() == null ? "" : ", " + SHORT_DATE_TIME.format(trip.getDepartureAt());
        return trip.getOriginLabel() + " " + ARROW + " " + trip.getDestLabel() + when;
    }

    /** Description : prix par place, places restantes, prenom du conducteur. */
    static String description(Trip trip) {
        int seats = trip.getSeatsAvailable();
        String seatsText = seats <= 0 ? "complet" : seats == 1 ? "1 place restante" : seats + " places restantes";
        String driver = trip.getDriver() != null && trip.getDriver().getFirstName() != null
                ? " Conducteur : " + trip.getDriver().getFirstName() + "." : "";
        return trip.getPricePerSeat() + " FCFA par place, " + seatsText + "." + driver + " Covoiturage Ekuiseo.";
    }

    static String render(Trip trip) {
        String tripUrl = PUBLIC_BASE_URL + "/trips/" + trip.getId();
        String title = escape(title(trip));
        String description = escape(description(trip));
        return "<!doctype html>\n<html lang=\"fr\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>" + title + " | Ekuiseo</title>\n"
                + "<meta name=\"description\" content=\"" + description + "\">\n"
                + "<meta property=\"og:type\" content=\"website\">\n"
                + "<meta property=\"og:site_name\" content=\"Ekuiseo\">\n"
                + "<meta property=\"og:title\" content=\"" + title + "\">\n"
                + "<meta property=\"og:description\" content=\"" + description + "\">\n"
                + "<meta property=\"og:url\" content=\"" + escape(tripUrl) + "\">\n"
                + "<meta property=\"og:image\" content=\"" + PUBLIC_BASE_URL + "/og-image.png\">\n"
                + "<meta property=\"og:locale\" content=\"fr_FR\">\n"
                + "<meta name=\"twitter:card\" content=\"summary_large_image\">\n"
                + "<meta name=\"twitter:title\" content=\"" + title + "\">\n"
                + "<meta name=\"twitter:description\" content=\"" + description + "\">\n"
                + "<meta name=\"twitter:image\" content=\"" + PUBLIC_BASE_URL + "/og-image.png\">\n"
                + "<link rel=\"canonical\" href=\"" + escape(tripUrl) + "\">\n"
                + "<meta http-equiv=\"refresh\" content=\"0;url=/trips/" + trip.getId() + "\">\n"
                + "</head>\n<body>\n"
                + "<p><a href=\"/trips/" + trip.getId() + "\">" + title + "</a></p>\n"
                + "</body>\n</html>\n";
    }

    static String notFoundHtml() {
        return "<!doctype html>\n<html lang=\"fr\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<title>Trajet introuvable | Ekuiseo</title>\n"
                + "<meta name=\"robots\" content=\"noindex\">\n"
                + "</head>\n<body>\n"
                + "<p>Ce trajet n existe pas ou n est plus disponible. <a href=\"/\">Rechercher un trajet sur Ekuiseo</a></p>\n"
                + "</body>\n</html>\n";
    }

    /** Echappement HTML minimal des cinq caracteres sensibles (libelles saisis par les conducteurs). */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
