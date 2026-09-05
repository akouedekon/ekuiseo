package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityVerificationResponse;
import bj.ekuiseo.api.dto.user.SubmitIdentityRequest;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Constats F601/F611 : pas de resoumission d un dossier valide, une par 24 h, depot journalise avec numero masque. */
class IdentityVerificationServiceTest {

    private final IdentityVerificationRepository repository = mock(IdentityVerificationRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final IdentityVerificationService service = new IdentityVerificationService(repository, userRepository, auditService);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").lastName("K").phone("+2290100000000").build();
    private final SubmitIdentityRequest request = new SubmitIdentityRequest(IdentityDocumentType.CNI, "B1234567");

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(repository.save(any(IdentityVerification.class))).thenAnswer(inv -> {
            IdentityVerification v = inv.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
    }

    @Test
    void firstSubmission_createsPendingRow_andAuditsMaskedNumber() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());

        IdentityVerificationResponse response = service.submit(user.getId(), request);

        assertThat(response.status()).isEqualTo(IdentityVerificationStatus.PENDING);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(user.getId()), eq("IDENTITY_SUBMITTED"), eq("identity_verification"), any(), details.capture());
        assertThat(details.getValue()).containsEntry("previousStatus", "NOT_SUBMITTED")
                .containsEntry("documentType", "CNI");
        assertThat((String) details.getValue().get("documentNumber")).endsWith("67").doesNotContain("B12345");
    }

    @Test
    void approvedDossier_cannotBeResubmitted() {
        IdentityVerification approved = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.APPROVED).documentType(IdentityDocumentType.CNI).documentNumber("B1234567")
                .submittedAt(Instant.now().minus(30, ChronoUnit.DAYS)).build();
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.submit(user.getId(), request)).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
        assertThat(approved.getStatus()).isEqualTo(IdentityVerificationStatus.APPROVED);
    }

    @Test
    void rejectedDossier_resubmittedAfter24h_becomesPending_andKeepsPreviousDecisionInAudit() {
        User admin = User.builder().id(UUID.randomUUID()).build();
        IdentityVerification rejected = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.REJECTED).documentType(IdentityDocumentType.PASSPORT).documentNumber("P999")
                .submittedAt(Instant.now().minus(2, ChronoUnit.DAYS)).reviewedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .reviewedBy(admin).rejectionReason("illisible").build();
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(rejected));

        IdentityVerificationResponse response = service.submit(user.getId(), request);

        assertThat(response.status()).isEqualTo(IdentityVerificationStatus.PENDING);
        assertThat(response.rejectionReason()).isNull();
        assertThat(rejected.getReviewedBy()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(user.getId()), eq("IDENTITY_SUBMITTED"), eq("identity_verification"), eq(rejected.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("previousStatus", "REJECTED")
                .containsEntry("previousDocumentType", "PASSPORT")
                .containsEntry("previousReviewedBy", admin.getId().toString());
    }

    @Test
    void resubmissionWithin24h_isRateLimited() {
        IdentityVerification pending = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
                .status(IdentityVerificationStatus.PENDING).documentNumber("B1")
                .submittedAt(Instant.now().minus(3, ChronoUnit.HOURS)).build();
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.submit(user.getId(), request)).isInstanceOf(TooManyRequestsException.class);
        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
