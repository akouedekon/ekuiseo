package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.NotificationService;
import bj.ekuiseo.api.service.RefreshTokenService;
import bj.ekuiseo.api.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F601 (retrait du badge) et F212 (suspension notifiee avec motif). */
class AdminUserServiceIdentityTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final IdentityVerificationRepository identityVerificationRepository = mock(IdentityVerificationRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final UserService userService = mock(UserService.class);
    private final AdminUserService service = new AdminUserService(userRepository, mock(VehicleRepository.class),
            mock(TripRepository.class), mock(BookingRepository.class), auditService, refreshTokenService,
            mock(BookingService.class), identityVerificationRepository, notificationService, userService);

    private final UUID adminId = UUID.randomUUID();
    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").lastName("K").phone("+2290100000000")
            .identityVerified(true).status(UserStatus.ACTIVE).build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(User.builder().id(adminId).build()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(identityVerificationRepository.save(any(IdentityVerification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void revokeIdentity_clearsBadge_rejectsDossier_auditsAndNotifies() {
        IdentityVerification approved = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.APPROVED).build();
        when(identityVerificationRepository.findByUserId(user.getId())).thenReturn(Optional.of(approved));

        AdminUserResponse response = service.revokeIdentity(adminId, user.getId(), "Piece signalee comme falsifiee");

        assertThat(response.identityVerified()).isFalse();
        assertThat(user.isIdentityVerified()).isFalse();
        assertThat(approved.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
        assertThat(approved.getRejectionReason()).isEqualTo("Piece signalee comme falsifiee");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(adminId), eq("USER_IDENTITY_REVOKED"), eq("user"), eq(user.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("hadBadge", true).containsEntry("previousVerificationStatus", "APPROVED");
        verify(notificationService).notify(eq(user), eq(NotificationType.IDENTITY_REVOKED), any());
    }

    @Test
    void revokeIdentity_withoutDossier_stillClearsBadge() {
        when(identityVerificationRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        service.revokeIdentity(adminId, user.getId(), "Verification manuelle annulee");

        assertThat(user.isIdentityVerified()).isFalse();
        verify(auditService).log(eq(adminId), eq("USER_IDENTITY_REVOKED"), eq("user"), eq(user.getId()), any());
    }

    @Test
    void suspend_notifiesCriticallyWithReason() {
        service.suspend(adminId, user.getId(), "Comportement abusif");

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(refreshTokenService).revokeAll(user.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyCritical(eq(user), eq(NotificationType.ACCOUNT_SUSPENDED), payload.capture());
        assertThat(payload.getValue()).containsEntry("reason", "Comportement abusif");
    }

    @Test
    void anonymize_delegatesToUserService_andReturnsAdminView() {
        service.anonymize(adminId, user.getId(), "Demande APDP");

        verify(userService).anonymize(user.getId(), adminId, "Demande APDP");
    }
}
