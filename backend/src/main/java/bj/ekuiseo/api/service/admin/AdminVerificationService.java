package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.dto.admin.AdminVerificationResponse;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite (regle metier n.19),
 * GET /api/v1/admin/verifications?status=..., POST .../approve, .../reject.
 * Une approbation positionne le badge {@code users.identity_verified} ; un rejet le
 * retire (constat F601). Les deux exigent un dossier PENDING (409 sinon) et previennent
 * l utilisateur (constat F212 : IDENTITY_APPROVED / IDENTITY_REJECTED avec motif).
 */
@Service
public class AdminVerificationService {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AdminVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                     UserRepository userRepository, AuditService auditService,
                                     NotificationService notificationService) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /**
     * Liste par statut (constat F210) : la file PENDING du plus ancien au plus recent
     * (ordre de traitement), l historique APPROVED/REJECTED de la decision la plus recente
     * a la plus ancienne. NOT_SUBMITTED n a jamais de ligne : liste vide.
     */
    @Transactional(readOnly = true)
    public List<AdminVerificationResponse> listByStatus(IdentityVerificationStatus status) {
        IdentityVerificationStatus effective = status == null ? IdentityVerificationStatus.PENDING : status;
        List<IdentityVerification> rows = effective == IdentityVerificationStatus.PENDING
                ? identityVerificationRepository.findByStatusOrderBySubmittedAtAsc(effective)
                : identityVerificationRepository.findByStatusOrderByReviewedAtDesc(effective);
        return rows.stream().map(this::toResponse).toList();
    }

    /** Alias historique de {@code listByStatus(PENDING)}. */
    @Transactional(readOnly = true)
    public List<AdminVerificationResponse> listPending() {
        return listByStatus(IdentityVerificationStatus.PENDING);
    }

    @Transactional
    public void approve(UUID adminId, UUID id) {
        IdentityVerification verification = findPending(id);
        verification.setStatus(IdentityVerificationStatus.APPROVED);
        verification.setReviewedAt(Instant.now());
        verification.setReviewedBy(userRepository.findById(adminId).orElse(null));
        verification.setRejectionReason(null);
        identityVerificationRepository.save(verification);

        User user = verification.getUser();
        user.setIdentityVerified(true);
        userRepository.save(user);

        auditService.log(adminId, "IDENTITY_VERIFICATION_APPROVED", "identity_verification", verification.getId(),
                Map.of("userId", user.getId().toString()));
        notificationService.notify(user, NotificationType.IDENTITY_APPROVED,
                Map.of("verificationId", verification.getId().toString()));
    }

    @Transactional
    public void reject(UUID adminId, UUID id, String reason) {
        IdentityVerification verification = findPending(id);
        verification.setStatus(IdentityVerificationStatus.REJECTED);
        verification.setReviewedAt(Instant.now());
        verification.setReviewedBy(userRepository.findById(adminId).orElse(null));
        verification.setRejectionReason(reason);
        identityVerificationRepository.save(verification);

        // Un dossier refuse retire le badge, quel que soit son origine (constat F601).
        User user = verification.getUser();
        user.setIdentityVerified(false);
        userRepository.save(user);

        auditService.log(adminId, "IDENTITY_VERIFICATION_REJECTED", "identity_verification", verification.getId(),
                Map.of("userId", user.getId().toString(), "reason", String.valueOf(reason)));
        notificationService.notify(user, NotificationType.IDENTITY_REJECTED,
                Map.of("verificationId", verification.getId().toString(), "reason", reason == null ? "" : reason));
    }

    private IdentityVerification findPending(UUID id) {
        IdentityVerification verification = identityVerificationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Verification introuvable"));
        if (verification.getStatus() != IdentityVerificationStatus.PENDING) {
            throw new ConflictException("Ce dossier a deja ete traite (" + verification.getStatus() + ")");
        }
        return verification;
    }

    private AdminVerificationResponse toResponse(IdentityVerification v) {
        User u = v.getUser();
        return new AdminVerificationResponse(v.getId(), u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(),
                v.getDocumentType(), v.getDocumentNumber(), v.getSubmittedAt(), v.getStatus(),
                v.getReviewedAt(), v.getRejectionReason(),
                v.getReviewedBy() != null ? v.getReviewedBy().getId() : null);
    }
}
