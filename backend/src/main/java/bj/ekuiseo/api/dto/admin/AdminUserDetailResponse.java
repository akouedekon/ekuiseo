package bj.ekuiseo.api.dto.admin;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.user.VehicleResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fiche complete d un utilisateur pour le back-office, GET /api/v1/admin/users/{id}
 * (constats F305/F306). Le numero de piece n est jamais expose en entier
 * ({@code identity.documentLast4}) ; les listes paginees (reservations, trajets,
 * paiements) sont servies par les sous-ressources /bookings, /trips, /payments.
 */
public record AdminUserDetailResponse(
        UUID id,
        String firstName,
        String lastName,
        String phone,
        String email,
        Role role,
        UserStatus status,
        String suspendedReason,
        Instant suspendedAt,
        Instant createdAt,
        Identity identity,
        boolean identityVerified,
        List<VehicleResponse> vehicles,
        List<PaymentAccountRef> paymentAccounts,
        long tripsPublished,
        long bookingsMade,
        BigDecimal ratingAvg,
        int lateCancellationsCount,
        Instant anonymizedAt
) {
    /** Dossier d identite (null si jamais soumis). */
    public record Identity(IdentityVerificationStatus status, IdentityDocumentType documentType, String documentLast4) {
    }

    public record PaymentAccountRef(UUID id, MobileMoneyOperator provider, String phone, boolean isDefault, boolean verified) {
    }
}
