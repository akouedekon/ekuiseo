package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Mise a jour partielle d'un trajet (PATCH) : chaque champ null est ignore (non
 * modifie). Les contraintes ci-dessous s'appliquent uniquement aux champs
 * effectivement fournis (le Bean Validation standard ignore les valeurs null pour
 * toutes ces annotations - c'est deliberement le cas y compris pour originLabel/
 * destLabel : @NotBlank aurait ete plus strict mais rejette aussi null, ce qui
 * casserait la semantique PATCH pour ces deux champs des qu'ils sont omis ; @Size(min=1)
 * a la place n'est verifiee que si le champ est fourni), et miroitent celles de
 * {@link CreateTripRequest} pour empecher qu'un PATCH n'introduise un prix negatif,
 * un nombre de places hors bornes, ou un libelle vide. Le rejet d'un libelle
 * uniquement compose d'espaces (equivalent a un blanc) est verifie explicitement
 * cote service (voir TripService#updateTrip), la ou l'appartenance null-vs-fourni
 * est deja distinguee.
 */
public record UpdateTripRequest(
        @Size(min = 1) String originLabel,
        Double originLat,
        Double originLng,
        @Size(min = 1) String destLabel,
        Double destLat,
        Double destLng,
        @Future Instant departureAt,
        @Min(1) @Max(8) Integer seatsTotal,
        @PositiveOrZero Long pricePerSeat,
        Boolean instantBooking,
        String luggagePolicy,
        String description
) {
}
