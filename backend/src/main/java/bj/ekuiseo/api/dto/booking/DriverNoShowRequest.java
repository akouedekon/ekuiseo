package bj.ekuiseo.api.dto.booking;

import jakarta.validation.constraints.Size;

/** POST /api/v1/bookings/{id}/driver-no-show : precision facultative du passager, transmise a la moderation. */
public record DriverNoShowRequest(
        @Size(max = 500) String details
) {
}
