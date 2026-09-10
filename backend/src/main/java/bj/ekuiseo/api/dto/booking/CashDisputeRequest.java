package bj.ekuiseo.api.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/bookings/{id}/cash/dispute : explication obligatoire du desaccord sur le solde en especes. */
public record CashDisputeRequest(
        @NotBlank(message = "Expliquez le desaccord") @Size(max = 1000) String details
) {
}
