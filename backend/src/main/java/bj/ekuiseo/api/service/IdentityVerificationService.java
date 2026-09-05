package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityVerificationResponse;
import bj.ekuiseo.api.dto.user.SubmitIdentityRequest;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Depot et consultation de la verification d'identite cote utilisateur (regle
 * metier n.19). L'absence de ligne pour un utilisateur signifie NOT_SUBMITTED.
 * Voir AdminVerificationService pour la moderation (approve/reject) cote admin.
 *
 * <p>Lot 1.4 (constats F601/F611) : un dossier APPROVED ne se resoumet pas (le badge
 * public ne doit pas survivre a un numero jamais revu ; c est a l administration de le
 * retirer d abord), une resoumission est bornee a une par 24 h, et chaque depot est
 * journalise (IDENTITY_SUBMITTED) avec l ancien statut et le numero masque.</p>
 */
@Service
public class IdentityVerificationService {

    /** Delai minimal entre deux soumissions d un meme utilisateur (constat F611). */
    static final Duration RESUBMIT_COOLDOWN = Duration.ofHours(24);

    private static final IdentityVerificationResponse NOT_SUBMITTED =
            new IdentityVerificationResponse(IdentityVerificationStatus.NOT_SUBMITTED, null, null, null, null);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public IdentityVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                        UserRepository userRepository, AuditService auditService) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public IdentityVerificationResponse get(UUID userId) {
        return identityVerificationRepository.findByUserId(userId).map(this::toResponse).orElse(NOT_SUBMITTED);
    }

    /**
     * Une seule ligne par utilisateur : une nouvelle soumission met a jour la precedente
     * plutot que de la dupliquer. 409 si le dossier est deja valide, 429 si la derniere
     * soumission date de moins de 24 h.
     */
    @Transactional
    public IdentityVerificationResponse submit(UUID userId, SubmitIdentityRequest req) {
        Instant now = Instant.now();
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        Map<String, Object> audit = new LinkedHashMap<>();
        if (verification == null) {
            User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
            verification = IdentityVerification.builder().user(user).build();
            audit.put("previousStatus", IdentityVerificationStatus.NOT_SUBMITTED.name());
        } else {
            if (verification.getStatus() == IdentityVerificationStatus.APPROVED) {
                throw new ConflictException("Votre identite est deja verifiee : aucune nouvelle soumission n est necessaire");
            }
            if (verification.getSubmittedAt() != null
                    && verification.getSubmittedAt().plus(RESUBMIT_COOLDOWN).isAfter(now)) {
                throw new TooManyRequestsException("Une soumission par 24 heures : reessayez plus tard");
            }
            audit.put("previousStatus", verification.getStatus().name());
            audit.put("previousDocumentType", String.valueOf(verification.getDocumentType()));
            audit.put("previousDocumentNumber", Masking.documentNumber(verification.getDocumentNumber()));
            if (verification.getReviewedBy() != null) {
                audit.put("previousReviewedBy", verification.getReviewedBy().getId().toString());
            }
        }
        verification.setDocumentType(req.documentType());
        verification.setDocumentNumber(req.documentNumber());
        verification.setStatus(IdentityVerificationStatus.PENDING);
        verification.setSubmittedAt(now);
        verification.setReviewedAt(null);
        verification.setReviewedBy(null);
        verification.setRejectionReason(null);
        verification = identityVerificationRepository.save(verification);

        audit.put("documentType", req.documentType().name());
        audit.put("documentNumber", Masking.documentNumber(req.documentNumber()));
        auditService.log(userId, "IDENTITY_SUBMITTED", "identity_verification", verification.getId(), audit);
        return toResponse(verification);
    }

    private IdentityVerificationResponse toResponse(IdentityVerification v) {
        return new IdentityVerificationResponse(v.getStatus(), v.getDocumentType(), v.getSubmittedAt(),
                v.getReviewedAt(), v.getRejectionReason());
    }
}
