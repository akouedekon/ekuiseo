package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;

import java.time.Instant;
import java.util.UUID;

/**
 * Trajet tel qu expose par l API.
 *
 * <p>{@code pickupStopId}, {@code dropoffStopId} et {@code segmentPriceFcfa} (constats
 * F115/F409) ne sont renseignes que par la recherche, et seulement quand la correspondance
 * passe par un arret intermediaire : identifiant de l arret de montee (null = origine du
 * trajet), de descente (null = destination) et prix du troncon
 * ({@code price_from_origin(descente) - price_from_origin(montee)}). Nuls partout ailleurs :
 * le prix a afficher est alors {@code pricePerSeat}.</p>
 */
public record TripResponse(
        UUID id,
        DriverSummary driver,
        VehicleSummary vehicle,
        TripType tripType,
        String originLabel,
        double originLat,
        double originLng,
        String destLabel,
        double destLat,
        double destLng,
        Instant departureAt,
        int seatsTotal,
        int seatsAvailable,
        long pricePerSeat,
        boolean instantBooking,
        String luggagePolicy,
        String description,
        TripStatus status,
        String recurrenceRule,
        Instant createdAt,
        /** Modele de navette dont ce trajet est une occurrence (null sinon). */
        UUID parentTripId,
        /** Renseigne uniquement a la creation d une navette : occurrences generees immediatement. */
        Integer generatedOccurrences,
        UUID pickupStopId,
        UUID dropoffStopId,
        Long segmentPriceFcfa
) {
    public TripResponse withGeneratedOccurrences(int count) {
        return new TripResponse(id, driver, vehicle, tripType, originLabel, originLat, originLng, destLabel, destLat, destLng,
                departureAt, seatsTotal, seatsAvailable, pricePerSeat, instantBooking, luggagePolicy, description, status,
                recurrenceRule, createdAt, parentTripId, count, pickupStopId, dropoffStopId, segmentPriceFcfa);
    }

    public TripResponse withSegment(UUID pickupStopId, UUID dropoffStopId, long segmentPriceFcfa) {
        return new TripResponse(id, driver, vehicle, tripType, originLabel, originLat, originLng, destLabel, destLat, destLng,
                departureAt, seatsTotal, seatsAvailable, pricePerSeat, instantBooking, luggagePolicy, description, status,
                recurrenceRule, createdAt, parentTripId, generatedOccurrences, pickupStopId, dropoffStopId, segmentPriceFcfa);
    }
}
