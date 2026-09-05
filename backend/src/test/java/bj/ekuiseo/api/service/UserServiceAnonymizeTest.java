package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F507 : anonymisation du compte, refus tant qu une obligation court, historique financier conserve. */
class UserServiceAnonymizeTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final UserPreferencesRepository userPreferencesRepository = mock(UserPreferencesRepository.class);
    private final PaymentAccountRepository paymentAccountRepository = mock(PaymentAccountRepository.class);
    private final SearchAlertRepository searchAlertRepository = mock(SearchAlertRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final IdentityVerificationRepository identityVerificationRepository = mock(IdentityVerificationRepository.class);
    private final DriverPayoutRepository driverPayoutRepository = mock(DriverPayoutRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserService service = new UserService(userRepository, vehicleRepository, tripRepository,
            bookingRepository, messageRepository, userPreferencesRepository, paymentAccountRepository,
            searchAlertRepository, notificationRepository, identityVerificationRepository, driverPayoutRepository,
            refreshTokenService, auditService, mock(UserMapper.class), mock(VehicleMapper.class));

    private final UUID userId = UUID.fromString("6f1c2a3e-9b4d-4c5e-8f60-1a2b3c4d5e6f");
    private final User user = User.builder().id(userId).phone("+2290197000322").email("awa@example.bj")
            .firstName("Awa").lastName("Kouassi").passwordHash("h").photoUrl("p").bio("b").emailVerified(true)
            .identityVerified(true).status(UserStatus.ACTIVE).suspendedReason("x")
            .pushSubscription(Map.of("endpoint", "e")).build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tripRepository.findByDriverIdAndStatusInAndDepartureAtAfter(eq(userId), anyList(), any())).thenReturn(List.of());
        when(messageRepository.redactBySender(eq(userId), any())).thenReturn(3);
        when(refreshTokenService.revokeAll(userId)).thenReturn(2);
    }

    @Test
    void anonymize_erasesPersonalData_keepsAccount_andRevokesSessions() {
        Vehicle vehicle = Vehicle.builder().id(UUID.randomUUID()).owner(user).plate("AB-1234-RB").photoUrl("v").build();
        when(vehicleRepository.findByOwnerId(userId)).thenReturn(List.of(vehicle));
        DriverPayout settled = DriverPayout.builder().id(UUID.randomUUID()).driver(user).amount(5000)
                .status(PayoutStatus.SETTLED).destinationMsisdn("+2290197000322").build();
        when(driverPayoutRepository.findByDriverIdOrderByRequestedAtDesc(userId)).thenReturn(List.of(settled));
        UserPreferences prefs = UserPreferences.builder().user(user).build();
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(prefs));

        service.anonymize(userId, userId, "Demande de l utilisateur");

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getPhone()).matches("\\+999\\d{12}").isEqualTo(UserService.anonymousPhone(userId));
        assertThat(user.getEmail()).isNull();
        assertThat(user.getFirstName()).isEqualTo("Utilisateur");
        assertThat(user.getLastName()).isEqualTo("supprime");
        assertThat(user.getPhotoUrl()).isNull();
        assertThat(user.getBio()).isNull();
        assertThat(user.getSuspendedReason()).isNull();
        assertThat(user.getPushSubscription()).isNull();
        assertThat(user.isIdentityVerified()).isFalse();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getPasswordHash()).isNotEqualTo("h");

        verify(paymentAccountRepository).deleteByUserId(userId);
        verify(searchAlertRepository).deleteByUserId(userId);
        verify(notificationRepository).deleteByUserId(userId);
        verify(userPreferencesRepository).delete(prefs);
        verify(identityVerificationRepository).deleteByUserId(userId);
        assertThat(vehicle.getPlate()).isEqualTo("********");
        assertThat(vehicle.getPhotoUrl()).isNull();
        assertThat(settled.getDestinationMsisdn()).endsWith("22").startsWith("*");
        verify(messageRepository).redactBySender(userId, "[message supprime]");
        verify(refreshTokenService).revokeAll(userId);
        // Reservations, paiements et avis : aucune suppression.
        verify(bookingRepository, never()).delete(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(userId), eq("USER_ANONYMIZED"), eq("user"), eq(userId), details.capture());
        assertThat(details.getValue()).containsEntry("self", true).containsEntry("messagesRedacted", 3)
                .containsEntry("sessionsRevoked", 2).containsEntry("vehiclesMasked", 1).containsEntry("payoutsMasked", 1);
        assertThat((String) details.getValue().get("previousPhone")).doesNotContain("0197000322");
    }

    @Test
    void anonymousPhone_isStable_andE164Compliant() {
        String phone = UserService.anonymousPhone(userId);
        assertThat(phone).hasSize(16).startsWith("+999").isEqualTo(UserService.anonymousPhone(userId));
        assertThat(phone).matches("^\\+[1-9][0-9]{7,14}$");
        assertThat(UserService.anonymousPhone(UUID.randomUUID())).isNotEqualTo(phone);
    }

    @Test
    void refused_whileUpcomingTrip() {
        when(tripRepository.findByDriverIdAndStatusInAndDepartureAtAfter(eq(userId), eq(List.of(TripStatus.PUBLISHED, TripStatus.FULL)), any()))
                .thenReturn(List.of(Trip.builder().id(UUID.randomUUID()).departureAt(Instant.now()).build()));

        assertThatThrownBy(() -> service.anonymize(userId, userId, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("trajet a venir");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(refreshTokenService, never()).revokeAll(any());
    }

    @Test
    void refused_whileActiveTemplate_activeBooking_orPendingPayout() {
        when(tripRepository.countByDriverIdAndStatus(userId, TripStatus.TEMPLATE)).thenReturn(1L);
        assertThatThrownBy(() -> service.assertCanBeAnonymized(userId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("navette");

        when(tripRepository.countByDriverIdAndStatus(userId, TripStatus.TEMPLATE)).thenReturn(0L);
        when(bookingRepository.existsByPassengerIdAndStatusIn(userId, List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED)))
                .thenReturn(true);
        assertThatThrownBy(() -> service.assertCanBeAnonymized(userId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("reservation en cours");

        when(bookingRepository.existsByPassengerIdAndStatusIn(any(), anyList())).thenReturn(false);
        when(driverPayoutRepository.existsByDriverIdAndStatusIn(userId, List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING)))
                .thenReturn(true);
        assertThatThrownBy(() -> service.assertCanBeAnonymized(userId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("reversement");
    }

    @Test
    void alreadyDeleted_isConflict() {
        user.setStatus(UserStatus.DELETED);

        assertThatThrownBy(() -> service.anonymize(userId, UUID.randomUUID(), "admin"))
                .isInstanceOf(ConflictException.class);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
