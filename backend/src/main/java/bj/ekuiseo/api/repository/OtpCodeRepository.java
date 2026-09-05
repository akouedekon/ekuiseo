package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    Optional<OtpCode> findFirstByPhoneAndPurposeAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String phone, String purpose, Instant now);

    @Modifying
    @Query("delete from OtpCode o where o.expiresAt < :before")
    int deleteByExpiresAtBefore(@Param("before") Instant before);
}
