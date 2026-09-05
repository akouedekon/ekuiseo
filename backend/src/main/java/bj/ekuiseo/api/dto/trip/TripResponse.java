package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.TripType;

import java.time.Instant;
import java.util.UUID;

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
        Integer generatedOccurrences
) {
    public TripResponse withGeneratedOccurrences(int count) {
        return new TripResponse(id, driver, vehicle, tripType, originLabel, originLat, originLng, destLabel, destLat, destLng,
                departureAt, seatsTotal, seatsAvailable, pricePerSeat, instantBooking, luggagePolicy, description, status,
                recurrenceRule, createdAt, parentTripId, count);
    }
}
