package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NoShowResolution;
import bj.ekuiseo.api.domain.enums.PassengerConfirmation;
import bj.ekuiseo.api.domain.enums.ComfortLevel;
import bj.ekuiseo.api.domain.enums.VehicleType;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.TripType;
import bj.ekuiseo.api.dto.payment.PaymentPlanResponse;
import bj.ekuiseo.api.dto.payment.RefundSummaryResponse;

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
        boolean reviewedByMe,
        /** Constat du passager apres le depart (V21) : PENDING (tacite apres 24 h), TRIP_DONE ou DRIVER_NO_SHOW. */
        PassengerConfirmation passengerConfirmation,
        Instant passengerConfirmedAt,
        /** Sort de l argent encaisse (V25) : remboursement en cours, manuel ou effectue ; null si rien n a ete decide. */
        RefundSummaryResponse refund,
        /** Dossier « conducteur absent » (V25) : echeance du remboursement automatique, contestation du conducteur, issue. */
        Instant driverNoShowRefundDueAt,
        Instant driverNoShowContestedAt,
        NoShowResolution driverNoShowResolution,
        Instant driverNoShowResolvedAt,
        /** Reglement du solde en especes a bord (contrat A.6, V27) ; null sans solde a bord. */
        CashSettlementResponse cash
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

    public record VehicleRef(String brand, String model, String color, ComfortLevel comfortLevel, VehicleType vehicleType) {
    }
}
