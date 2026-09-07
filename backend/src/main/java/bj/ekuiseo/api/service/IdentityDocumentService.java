package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.ServiceUnavailableException;
import bj.ekuiseo.api.domain.IdentityDocument;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityDocumentSummary;
import bj.ekuiseo.api.repository.IdentityDocumentRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.service.storage.IdentityDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pieces d identite televersees (V20, point n.20 de l audit) : depot et retrait par
 * l utilisateur tant que son dossier est PENDING, consultation par le back-office (chaque
 * lecture du contenu est journalisee ADMIN_IDENTITY_DOCUMENT_VIEWED), suppression a
 * l anonymisation et 30 jours apres la decision (RetentionScheduler). Le contenu est chiffre
 * sur disque par {@link IdentityDocumentStorage} ; le type est etabli par les octets magiques,
 * jamais par ce que declare le client.
 */
@Service
public class IdentityDocumentService {

    private static final Logger log = LoggerFactory.getLogger(IdentityDocumentService.class);

    /** 5 Mo : au-dela, une photo de telephone se compresse ; aligne sur spring.servlet.multipart.max-file-size. */
    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;
    public static final String AUDIT_VIEWED = "ADMIN_IDENTITY_DOCUMENT_VIEWED";

    /** Contenu dechiffre d une piece, servi en flux au back-office. */
    public record StoredDocument(IdentityDocumentSide side, String contentType, byte[] content) {
    }

    private final IdentityDocumentRepository identityDocumentRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityDocumentStorage storage;
    private final AuditService auditService;

    public IdentityDocumentService(IdentityDocumentRepository identityDocumentRepository,
                                   IdentityVerificationRepository identityVerificationRepository,
                                   IdentityDocumentStorage storage, AuditService auditService) {
        this.identityDocumentRepository = identityDocumentRepository;
        this.identityVerificationRepository = identityVerificationRepository;
        this.storage = storage;
        this.auditService = auditService;
    }

    public boolean isEnabled() {
        return storage.isEnabled();
    }

    /**
     * Depose (ou remplace) la piece d une face. 503 si le stockage n est pas configure, 404 sans
     * dossier, 409 si le dossier n est plus PENDING, 400 si le contenu est vide, trop gros ou
     * d un format non reconnu.
     */
    @Transactional
    public IdentityDocumentSummary upload(UUID userId, IdentityDocumentSide side, byte[] content) {
        if (!storage.isEnabled()) {
            throw new ServiceUnavailableException("Le televersement des pieces d identite n est pas active sur ce serveur");
        }
        if (side == null) {
            throw new BadRequestException("Face a preciser : FRONT, BACK ou SELFIE");
        }
        IdentityVerification verification = pendingVerificationOf(userId);
        if (content == null || content.length == 0) {
            throw new BadRequestException("Fichier vide");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw new BadRequestException("Fichier trop volumineux : 5 Mo au maximum");
        }
        String contentType = IdentityDocumentStorage.sniffContentType(content);
        if (contentType == null) {
            throw new BadRequestException("Format non reconnu : JPEG, PNG, WebP ou PDF attendu");
        }

        String storageKey;
        try {
            storageKey = storage.store(content);
        } catch (IOException ex) {
            log.error("Ecriture d une piece d identite impossible", ex);
            throw new ServiceUnavailableException("Stockage des pieces d identite momentanement indisponible");
        }

        IdentityDocument document = identityDocumentRepository.findByVerificationIdAndSide(verification.getId(), side)
                .orElse(null);
        String previousKey = null;
        if (document == null) {
            document = IdentityDocument.builder().verification(verification).side(side).build();
        } else {
            previousKey = document.getStorageKey();
        }
        document.setContentType(contentType);
        document.setSizeBytes(content.length);
        document.setStorageKey(storageKey);
        document.setSha256(IdentityDocumentStorage.sha256Hex(content));
        document = identityDocumentRepository.save(document);
        if (previousKey != null) {
            storage.delete(previousKey);
        }

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("side", side.name());
        audit.put("contentType", contentType);
        audit.put("sizeBytes", content.length);
        audit.put("replaced", previousKey != null);
        auditService.log(userId, "IDENTITY_DOCUMENT_UPLOADED", "identity_verification", verification.getId(), audit);
        return toSummary(document);
    }

    /** Retire une piece tant que le dossier est PENDING (409 sinon, 404 si absente). */
    @Transactional
    public void delete(UUID userId, IdentityDocumentSide side) {
        IdentityVerification verification = pendingVerificationOf(userId);
        IdentityDocument document = identityDocumentRepository.findByVerificationIdAndSide(verification.getId(), side)
                .orElseThrow(() -> new NotFoundException("Aucune piece pour cette face"));
        identityDocumentRepository.delete(document);
        storage.delete(document.getStorageKey());
        auditService.log(userId, "IDENTITY_DOCUMENT_DELETED", "identity_verification", verification.getId(),
                Map.of("side", side.name()));
    }

    /** Presence des pieces d un dossier (reponses utilisateur et back-office). */
    @Transactional(readOnly = true)
    public List<IdentityDocumentSummary> summaries(UUID verificationId) {
        if (verificationId == null) return List.of();
        return identityDocumentRepository.findByVerificationIdOrderBySideAsc(verificationId).stream()
                .map(IdentityDocumentService::toSummary).toList();
    }

    /** Liste back-office (404 si le dossier n existe pas). La liste ne revele rien : pas d audit, contrairement a la lecture. */
    @Transactional(readOnly = true)
    public List<IdentityDocumentSummary> listForAdmin(UUID verificationId) {
        IdentityVerification verification = identityVerificationRepository.findById(verificationId)
                .orElseThrow(() -> new NotFoundException("Verification introuvable"));
        return summaries(verification.getId());
    }

    /**
     * Contenu dechiffre d une piece pour le back-office. Chaque lecture est journalisee
     * ({@value #AUDIT_VIEWED} : administrateur, dossier, utilisateur, face) - c est la
     * contrepartie de l acces a une donnee sensible (docs/CONFORMITE.md).
     */
    @Transactional(readOnly = true)
    public StoredDocument openForAdmin(UUID adminId, UUID verificationId, IdentityDocumentSide side) {
        IdentityVerification verification = identityVerificationRepository.findById(verificationId)
                .orElseThrow(() -> new NotFoundException("Verification introuvable"));
        IdentityDocument document = identityDocumentRepository.findByVerificationIdAndSide(verification.getId(), side)
                .orElseThrow(() -> new NotFoundException("Aucune piece pour cette face"));
        if (!storage.isEnabled()) {
            throw new ServiceUnavailableException("Le stockage des pieces d identite n est pas active sur ce serveur");
        }
        byte[] content;
        try {
            content = storage.read(document.getStorageKey());
        } catch (IOException ex) {
            log.error("Lecture de la piece {} du dossier {} impossible", side, verificationId, ex);
            throw new NotFoundException("Fichier de la piece introuvable ou illisible");
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("userId", verification.getUser().getId().toString());
        audit.put("side", side.name());
        audit.put("contentType", document.getContentType());
        auditService.log(adminId, AUDIT_VIEWED, "identity_verification", verification.getId(), audit);
        return new StoredDocument(side, document.getContentType(), content);
    }

    /** Anonymisation (UserService#anonymize) : fichiers et lignes, avant la suppression du dossier. */
    @Transactional
    public int deleteAllForUser(UUID userId) {
        return deleteAll(identityDocumentRepository.findByUserId(userId));
    }

    /** Purge nocturne (RetentionScheduler) : pieces des dossiers decides depuis avant {@code cutoff}. */
    @Transactional
    public int purgeDecidedBefore(Instant cutoff) {
        return deleteAll(identityDocumentRepository.findDecidedBefore(cutoff));
    }

    private int deleteAll(List<IdentityDocument> documents) {
        for (IdentityDocument document : documents) {
            identityDocumentRepository.delete(document);
            storage.delete(document.getStorageKey());
        }
        return documents.size();
    }

    private IdentityVerification pendingVerificationOf(UUID userId) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Soumettez d abord le type et le numero de votre piece"));
        if (verification.getStatus() != IdentityVerificationStatus.PENDING) {
            throw new ConflictException("Le dossier a deja ete traite (" + verification.getStatus() + ") : les pieces ne se modifient plus");
        }
        return verification;
    }

    static IdentityDocumentSummary toSummary(IdentityDocument d) {
        return new IdentityDocumentSummary(d.getSide(), d.getContentType(), d.getSizeBytes(), d.getCreatedAt());
    }
}
