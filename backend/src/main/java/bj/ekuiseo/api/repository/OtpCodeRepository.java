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

    /**
     * Invalide tous les codes encore actifs d un numero pour un usage (constat F540) : un
     * nouveau code remplace les precedents, en une requete, dans la transaction d emission.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OtpCode o set o.consumedAt = :now where o.phone = :phone and o.purpose = :purpose and o.consumedAt is null")
    int consumeActive(@Param("phone") String phone, @Param("purpose") String purpose, @Param("now") Instant now);

    /** Codes emis pour un numero depuis un instant (plafond quotidien, constat F512), calcule en base pour survivre aux redeploiements. */
    long countByPhoneAndCreatedAtAfter(String phone, Instant after);
}
