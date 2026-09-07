package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, UUID> {
    List<DriverPayout> findByDriverIdOrderByRequestedAtDesc(UUID driverId);
    /** Reversements non soldes d un conducteur : bloquent l anonymisation (UserService#anonymize). */
    boolean existsByDriverIdAndStatusIn(UUID driverId, java.util.List<bj.ekuiseo.api.domain.enums.PayoutStatus> statuses);

    /**
     * Verrou consultatif lie a la transaction courante (constat F303) : {@code true} si obtenu,
     * {@code false} si un autre lot est en cours de constitution. Libere automatiquement a la
     * fin de la transaction (pg_advisory_xact_lock ne se libere pas a la main).
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryLockBatch(@Param("key") long key);

    long countByStatus(bj.ekuiseo.api.domain.enums.PayoutStatus status);

    /** Montant total du aux conducteurs dans un statut (GET /api/v1/admin/overview). */
    @Query("select coalesce(sum(p.amount), 0L) from DriverPayout p where p.status = :status")
    long sumAmountByStatus(@Param("status") bj.ekuiseo.api.domain.enums.PayoutStatus status);

    /** Reversements d un conducteur, pour l export de ses donnees (UserDataExportService). */
    java.util.List<DriverPayout> findByDriverIdOrderByRequestedAtAsc(UUID driverId);
}
