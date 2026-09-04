package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, UUID> {
    Optional<IdentityVerification> findByUserId(UUID userId);
    List<IdentityVerification> findByStatusOrderBySubmittedAtAsc(IdentityVerificationStatus status);
}
