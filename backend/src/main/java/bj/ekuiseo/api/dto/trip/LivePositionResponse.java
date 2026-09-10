package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;

import java.time.Instant;
import java.util.List;

/**
 * Instantane du suivi en direct pour le conducteur et ses passagers confirmes
 * (GET /api/v1/trips/{id}/live, V23, enrichi en V28). {@code position} est la derniere
 * position du conducteur, null tant qu aucune n a ete recue ; {@code staleSeconds} est
 * son age a l instant de la reponse (null sans position) - le front la declare perimee
 * au-dela de 90 s. {@code shareToken} n est renseigne que si le partage est actif.
 *
 * <p>V28 : {@code participants} liste les positions visibles par l appelant selon son
 * role (un passager voit le conducteur et lui-meme, le conducteur voit chaque passager
 * qui partage) ; {@code intervalSeconds} est la cadence recommandee ; {@code serverTime}
 * permet au front d estimer le decalage de son horloge sans jamais comparer celle du
 * conducteur a la sienne.</p>
 */
public record LivePositionResponse(
        boolean enabled,
        Position position,
        Long staleSeconds,
        TripStatus tripStatus,
        Instant departureAt,
        String shareToken,
        int intervalSeconds,
        List<LiveParticipant> participants,
        Instant serverTime
) {
    public record Position(
            double lat,
            double lng,
            Float heading,
            Float speedKmh,
            Float accuracyM,
            Instant recordedAt
    ) {
    }
}
