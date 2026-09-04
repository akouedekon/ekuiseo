package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityVerificationResponse;
import bj.ekuiseo.api.dto.user.SubmitIdentityRequest;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Depot et consultation de la verification d'identite cote utilisateur (regle
 * metier n.19). L'absence de ligne pour un utilisateur signifie NOT_SUBMITTED.
 * Voir AdminVerificationService pour la moderation (approve/reject) cote admin.
 */
@Service
public class IdentityVerificationService {

    private static final IdentityVerificationResponse NOT_SUBMITTED =
            new IdentityVerificationResponse(IdentityVerificationStatus.NOT_SUBMITTED, null, null, null, null);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final UserRepository userRepository;

    public IdentityVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                        UserRepository userRepository) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public IdentityVerificationResponse get(UUID userId) {
        return identityVerificationRepository.findByUserId(userId).map(this::toResponse).orElse(NOT_SUBMITTED);
    }

    /** Une seule ligne par utilisateur : une nouvelle soumission met a jour la precedente plutot que de la dupliquer. */
    @Transactional
    public IdentityVerificationResponse submit(UUID userId, SubmitIdentityRequest req) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElseGet(() -> {
            User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
            return IdentityVerification.builder().user(user).build();
        });
        verification.setDocumentType(req.documentType());
        verification.setDocumentNumber(req.documentNumber());
        verification.setStatus(IdentityVerificationStatus.PENDING);
        verification.setSubmittedAt(Instant.now());
        verification.setReviewedAt(null);
        verification.setReviewedBy(null);
        verification.setRejectionReason(null);
        return toResponse(identityVerificationRepository.save(verification));
    }

    private IdentityVerificationResponse toResponse(IdentityVerification v) {
        return new IdentityVerificationResponse(v.getStatus(), v.getDocumentType(), v.getSubmittedAt(),
                v.getReviewedAt(), v.getRejectionReason());
    }
}
