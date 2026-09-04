package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Verification d'identite d'un utilisateur : une ligne par utilisateur, mise a
 * jour (jamais dupliquee) a chaque nouvelle soumission. L'absence de ligne pour
 * un utilisateur signifie NOT_SUBMITTED (voir IdentityVerificationService).
 *
 * <p><b>Limitation connue</b> : aucun sous-systeme de stockage de fichiers n'est
 * branche ici (pas de bucket S3/objet compatible dans ce projet) : seul le
 * numero de document est enregistre, jamais une photo/scan. Le televersement du
 * justificatif reste un TODO documente (voir README "Ce qui reste a faire").</p>
 */
@Entity
@Table(name = "identity_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityVerification {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 30)
    private IdentityDocumentType documentType;

    @Column(name = "document_number", length = 100)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private IdentityVerificationStatus status = IdentityVerificationStatus.PENDING;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;
}
