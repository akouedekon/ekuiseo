package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bornes de texte alignees sur les colonnes de {@code trips} (V1) ; coordonnees dans les plages geographiques (constat F026). */
public record CreateTripRequest(
        @NotNull UUID vehicleId,
        @NotNull TripType tripType,
        @NotBlank @Size(max = 255) String originLabel,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double originLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double originLng,
        @NotBlank @Size(max = 255) String destLabel,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destLng,
        @NotNull @Future Instant departureAt,
        @Min(1) @Max(8) int seatsTotal,
        @Positive(message = "Le prix par place doit etre superieur a 0 F") long pricePerSeat,
        boolean instantBooking,
        @Size(max = 2000) String luggagePolicy,
        @Size(max = 2000) String description,
        /** Regle RRULE (RFC 5545), utilisee uniquement pour les trajets QUOTIDIEN. */
        @Size(max = 255) String recurrenceRule,
        @Valid @Size(max = 10) List<StopRequest> stops
) {
}
