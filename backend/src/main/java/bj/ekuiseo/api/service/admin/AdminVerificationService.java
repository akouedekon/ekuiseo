package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.admin.AdminVerificationResponse;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite (regle metier n.19),
 * GET /api/v1/admin/verifications?status=PENDING, POST .../approve, .../reject.
 * Une approbation positionne le badge {@code users.identity_verified} (meme
 * effet que AdminUserService#verifyIdentity, ici trace depuis une soumission
 * effective plutot qu'actionne "a l'aveugle" par l'admin).
 */
@Service
public class AdminVerificationService {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AdminVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                     UserRepository userRepository, AuditService auditService) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AdminVerificationResponse> listPending() {
        return identityVerificationRepository.findByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus.PENDING)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public void approve(UUID adminId, UUID id) {
        IdentityVerification verification = find(id);
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
    }

    @Transactional
    public void reject(UUID adminId, UUID id, String reason) {
        IdentityVerification verification = find(id);
        verification.setStatus(IdentityVerificationStatus.REJECTED);
        verification.setReviewedAt(Instant.now());
        verification.setReviewedBy(userRepository.findById(adminId).orElse(null));
        verification.setRejectionReason(reason);
        identityVerificationRepository.save(verification);

        auditService.log(adminId, "IDENTITY_VERIFICATION_REJECTED", "identity_verification", verification.getId(),
                Map.of("reason", String.valueOf(reason)));
    }

    private IdentityVerification find(UUID id) {
        return identityVerificationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Verification introuvable"));
    }

    private AdminVerificationResponse toResponse(IdentityVerification v) {
        User u = v.getUser();
        return new AdminVerificationResponse(v.getId(), u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(),
                v.getDocumentType(), v.getDocumentNumber(), v.getSubmittedAt(), v.getStatus());
    }
}
