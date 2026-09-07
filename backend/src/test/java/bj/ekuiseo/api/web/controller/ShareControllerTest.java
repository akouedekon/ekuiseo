package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Constat F341 : apercu Open Graph d un trajet, echappe, 404 sobre pour un trajet non partageable. */
class ShareControllerTest {

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final ShareController controller = new ShareController(tripRepository);

    private Trip trip(TripStatus status) {
        return Trip.builder().id(UUID.randomUUID()).driver(User.builder().firstName("Koffi").build())
                .originLabel("Cotonou").destLabel("Bohicon <b>\"centre\"</b>")
                .departureAt(Instant.parse("2026-09-12T06:30:00Z")).seatsAvailable(2).pricePerSeat(2500)
                .status(status).build();
    }

    @Test
    void title_isRouteAndBeninTime() {
        Trip trip = trip(TripStatus.PUBLISHED);
        assertThat(ShareController.title(trip)).isEqualTo("Cotonou " + ShareController.ARROW + " Bohicon <b>\"centre\"</b>, sam. 12 sept. 07:30");
        assertThat(ShareController.description(trip)).isEqualTo("2500 FCFA par place, 2 places restantes. Conducteur : Koffi. Covoiturage Ekuiseo.");
    }

    @Test
    void render_carriesOpenGraphTags_escaped_andRedirects() {
        Trip trip = trip(TripStatus.PUBLISHED);
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));

        ResponseEntity<String> res = controller.trip(trip.getId());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/html");
        String html = res.getBody();
        assertThat(html).contains("<meta property=\"og:title\" content=\"Cotonou " + ShareController.ARROW + " Bohicon &lt;b&gt;&quot;centre&quot;&lt;/b&gt;, sam. 12 sept. 07:30\">")
                .contains("<meta property=\"og:url\" content=\"https://ekuiseo.com/trips/" + trip.getId() + "\">")
                .contains("<meta property=\"og:image\" content=\"https://ekuiseo.com/og-image.png\">")
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
                .contains("<meta http-equiv=\"refresh\" content=\"0;url=/trips/" + trip.getId() + "\">")
                .contains("og:description")
                .doesNotContain("<b>");
    }

    @Test
    void draftTemplateOrUnknownTrip_is404Html() {
        Trip template = trip(TripStatus.TEMPLATE);
        when(tripRepository.findById(template.getId())).thenReturn(Optional.of(template));
        assertThat(controller.trip(template.getId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Trip draft = trip(TripStatus.DRAFT);
        when(tripRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        ResponseEntity<String> res = controller.trip(draft.getId());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("introuvable").doesNotContain("Bohicon");

        assertThat(controller.trip(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void escape_handlesTheFiveSensitiveCharacters() {
        assertThat(ShareController.escape("a & b < c > d \" e ' f")).isEqualTo("a &amp; b &lt; c &gt; d &quot; e &#39; f");
        assertThat(ShareController.escape(null)).isEmpty();
    }
}
