package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.dto.admin.AdminVerificationResponse;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.NotificationService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constats F601 (garde d etat, rejet retire le badge), F212 (notifications) et F210 (liste par statut). */
class AdminVerificationServiceTest {

    private final IdentityVerificationRepository repository = mock(IdentityVerificationRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AdminVerificationService service =
            new AdminVerificationService(repository, userRepository, auditService, notificationService);

    private final UUID adminId = UUID.randomUUID();
    private final User admin = User.builder().id(adminId).firstName("Admin").lastName("E").build();
    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").lastName("K").phone("+2290100000000")
            .identityVerified(true).build();
    private IdentityVerification pending;

    @BeforeEach
    void setUp() {
        pending = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.PENDING).submittedAt(Instant.now()).build();
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(repository.save(any(IdentityVerification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approve_setsBadge_andNotifies() {
        user.setIdentityVerified(false);

        service.approve(adminId, pending.getId());

        assertThat(pending.getStatus()).isEqualTo(IdentityVerificationStatus.APPROVED);
        assertThat(pending.getReviewedBy()).isSameAs(admin);
        assertThat(user.isIdentityVerified()).isTrue();
        verify(notificationService).notify(eq(user), eq(NotificationType.IDENTITY_APPROVED), any());
    }

    @Test
    void reject_clearsBadge_andNotifiesWithReason() {
        service.reject(adminId, pending.getId(), "Photo illisible");

        assertThat(pending.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
        assertThat(pending.getRejectionReason()).isEqualTo("Photo illisible");
        assertThat(user.isIdentityVerified()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(user), eq(NotificationType.IDENTITY_REJECTED), payload.capture());
        assertThat(payload.getValue()).containsEntry("reason", "Photo illisible");
    }

    @Test
    void approveOrReject_requirePending() {
        pending.setStatus(IdentityVerificationStatus.APPROVED);

        assertThatThrownBy(() -> service.approve(adminId, pending.getId())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reject(adminId, pending.getId(), "x")).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any());
        assertThat(user.isIdentityVerified()).isTrue();
    }

    @Test
    void listByStatus_usesQueueOrderForPending_andDecisionOrderForHistory() {
        IdentityVerification decided = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.REJECTED).submittedAt(Instant.now()).reviewedAt(Instant.now())
                .reviewedBy(admin).rejectionReason("motif").build();
        when(repository.findByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus.PENDING)).thenReturn(List.of(pending));
        when(repository.findByStatusOrderByReviewedAtDesc(IdentityVerificationStatus.REJECTED)).thenReturn(List.of(decided));

        List<AdminVerificationResponse> queue = service.listByStatus(null);
        List<AdminVerificationResponse> history = service.listByStatus(IdentityVerificationStatus.REJECTED);

        assertThat(queue).extracting(AdminVerificationResponse::id).containsExactly(pending.getId());
        assertThat(queue.get(0).reviewedAt()).isNull();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).rejectionReason()).isEqualTo("motif");
        assertThat(history.get(0).reviewedAt()).isNotNull();
        assertThat(history.get(0).reviewedBy()).isEqualTo(adminId);
    }
}
