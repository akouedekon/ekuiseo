package bj.ekuiseo.api.dto.trip;

import jakarta.validation.constraints.NotNull;

/** PUT /api/v1/trips/{id}/live : active ou coupe le partage de position du conducteur (V23). */
public record LiveSharingRequest(
        @NotNull(message = "enabled est obligatoire") Boolean enabled
) {
}
