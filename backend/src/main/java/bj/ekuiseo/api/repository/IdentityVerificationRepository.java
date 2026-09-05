package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
