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
        Instant createdAt
) {
}
