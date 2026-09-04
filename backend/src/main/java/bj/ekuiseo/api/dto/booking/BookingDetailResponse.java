package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reservation enrichie du trajet et du plan de paiement, pour eviter un N+1
 * cote front sur "Mes reservations" (GET /api/v1/bookings?expand=trip,paymentPlan
 * et GET /api/v1/bookings/{id}?expand=trip,paymentPlan).
 *
 * <p><b>Simplification assumee</b> : le parametre {@code expand} est accepte
 * (pour compatibilite avec le contrat front) mais {@code trip} et
 * {@code paymentPlan} sont TOUJOURS renvoyes, quelle que soit sa valeur - la
 * requete sous-jacente (BookingRepository#findByPassengerIdWithTripFetched)
 * charge deja tout par JOIN FETCH en une seule requete, donc il n'y a aucun cout
 * a toujours enrichir plutot qu'a conditionner sur la presence du parametre.</p>
 */
public record BookingDetailResponse(
        UUID id,
        UUID tripId,
        UUID passengerId,
        int seats,
        long amount,
        long serviceFee,
        BookingStatus status,
        PaymentMethod paymentMethod,
        Instant createdAt,
        PaymentPlanResponse paymentPlan,
        TripSummary trip,
        long unreadMessages,
        /** Vrai si le demandeur a deja note le conducteur pour ce trajet (un seul avis par trajet et par cible). */
        boolean reviewedByMe
) {

    public record TripSummary(
            UUID id,
            TripType tripType,
            String originLabel,
            String destLabel,
            Instant departureAt,
            long pricePerSeat,
            DriverRef driver,
            VehicleRef vehicle
    ) {
    }

    public record DriverRef(UUID id, String firstName, String lastName, String photoUrl, BigDecimal ratingAvg) {
    }

    public record VehicleRef(String brand, String model, String color, ComfortLevel comfortLevel) {
    }
}
