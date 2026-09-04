package bj.ekuiseo.api.dto.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Votre trajet de la semaine" (GET /api/v1/me/recurring-trips) : axe
 * origine/destination emprunte au moins deux fois par le passager, avec la
 * prochaine offre disponible sur ce meme axe. Detecte a la volee a partir de
 * l'historique de reservations (voir BookingService#myRecurringTrips) : {@code id}
 * n'identifie donc pas une ligne stockee, mais est deterministe (derive du
 * passager et de l'axe) pour rester stable d'un appel a l'autre.
 *
 * @param weekdays jours actifs, 1 = lundi ... 7 = dimanche (ISO-8601)
 * @param departureTime heure de depart habituelle (HH:mm:ss, UTC)
 * @param matchesAvailable nombre d'offres PUBLISHED actuellement disponibles sur cet axe
 */
public record RecurringTripResponse(
        UUID id,
        String originLabel,
        double originLat,
        double originLng,
        String destLabel,
        double destLat,
        double destLng,
        List<Integer> weekdays,
        String departureTime,
        int seats,
        long matchesAvailable,
        Instant nextDepartureAt
) {
}
