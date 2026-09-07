package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.IdentityDocumentType;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, UUID> {
    Optional<IdentityVerification> findByUserId(UUID userId);
    /** File de traitement : du plus ancien au plus recent. */
    List<IdentityVerification> findByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus status);
    /** Historique des decisions : de la plus recente a la plus ancienne (constat F210). */
    List<IdentityVerification> findByStatusOrderByReviewedAtDesc(IdentityVerificationStatus status);
    /** Anonymisation d un compte (UserService#anonymize) : le numero de piece est efface. */
    void deleteByUserId(UUID userId);

    /** Taille de la file (GET /api/v1/admin/overview). */
    long countByStatus(IdentityVerificationStatus status);

    /** Dossier le plus ancien dans un statut (anciennete de la file, GET /api/v1/admin/overview). */
    Optional<IdentityVerification> findFirstByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus status);

    /**
     * Autres comptes ayant declare la meme piece (meme type, meme numero normalise, tous
     * statuts), constat F604 : servi par l index (document_type, upper(document_number)) de V16.
     */
    @Query("select v.user.id from IdentityVerification v where v.documentType = :type "
            + "and upper(v.documentNumber) = upper(:number) and v.user.id <> :userId")
    List<UUID> findOtherUserIdsWithDocument(@Param("type") IdentityDocumentType type,
                                            @Param("number") String number,
                                            @Param("userId") UUID userId);
}
