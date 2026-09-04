package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateTripRequest(
        @NotNull UUID vehicleId,
        @NotNull TripType tripType,
        @NotBlank String originLabel,
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotBlank String destLabel,
        @NotNull Double destLat,
        @NotNull Double destLng,
        @NotNull @Future Instant departureAt,
        @Min(1) @Max(8) int seatsTotal,
        @PositiveOrZero long pricePerSeat,
        boolean instantBooking,
        String luggagePolicy,
        String description,
        /** Regle RRULE (RFC 5545), utilisee uniquement pour les trajets QUOTIDIEN. */
        String recurrenceRule,
        List<StopRequest> stops
) {
}
