package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    Optional<OtpCode> findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String phone, Instant now);
}
