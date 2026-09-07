package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.IdentityDocument;
import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityDocumentRepository extends JpaRepository<IdentityDocument, UUID> {
    /** Pieces d un dossier, dans l ordre recto, verso, selfie (ordre de l enum). */
    List<IdentityDocument> findByVerificationIdOrderBySideAsc(UUID verificationId);

    Optional<IdentityDocument> findByVerificationIdAndSide(UUID verificationId, IdentityDocumentSide side);

    /** Pieces d un utilisateur (anonymisation) : via son dossier unique. */
    @Query("select d from IdentityDocument d where d.verification.user.id = :userId")
    List<IdentityDocument> findByUserId(@Param("userId") UUID userId);

    /**
     * Pieces dont le dossier est decide (APPROVED ou REJECTED) depuis avant {@code cutoff} :
     * purgees par RetentionScheduler (30 jours, docs/CONFORMITE.md 3.2). Un dossier resoumis
     * repasse PENDING avec reviewed_at a null et sort donc de la purge.
     */
    @Query("select d from IdentityDocument d where d.verification.status <> bj.ekuiseo.api.domain.enums.IdentityVerificationStatus.PENDING "
            + "and d.verification.reviewedAt is not null and d.verification.reviewedAt < :cutoff")
    List<IdentityDocument> findDecidedBefore(@Param("cutoff") Instant cutoff);
}
