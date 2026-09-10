package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayout;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, UUID> {
    List<DriverPayout> findByDriverIdOrderByRequestedAtDesc(UUID driverId);

    /** Vue back-office paginee (constats F119/F308/F237) : conducteur charge avec le lot ; le tri vient du Pageable (requestedAt desc). */
    @EntityGraph(attributePaths = "driver")
    @Query(value = "select p from DriverPayout p", countQuery = "select count(p) from DriverPayout p")
    Page<DriverPayout> findAllWithDriver(Pageable pageable);

    @EntityGraph(attributePaths = "driver")
    @Query(value = "select p from DriverPayout p where p.status = :status",
            countQuery = "select count(p) from DriverPayout p where p.status = :status")
    Page<DriverPayout> findByStatusWithDriver(@Param("status") bj.ekuiseo.api.domain.enums.PayoutStatus status, Pageable pageable);
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

    /** Montant des lots d un conducteur dans les statuts donnes (revenus, contrat A.8). */
    @Query("select coalesce(sum(p.amount), 0L) from DriverPayout p where p.driver.id = :driverId and p.status in :statuses")
    long sumAmountByDriverAndStatusIn(@Param("driverId") UUID driverId,
                                      @Param("statuses") java.util.List<bj.ekuiseo.api.domain.enums.PayoutStatus> statuses);

    /** Total effectivement vire a un conducteur (lots SETTLED, montant regle sinon montant du lot). */
    @Query("select coalesce(sum(coalesce(p.settledAmount, p.amount)), 0L) from DriverPayout p "
            + "where p.driver.id = :driverId and p.status = bj.ekuiseo.api.domain.enums.PayoutStatus.SETTLED")
    long sumSettledByDriver(@Param("driverId") UUID driverId);

    /** Reversements d un conducteur, pour l export de ses donnees (UserDataExportService). */
    java.util.List<DriverPayout> findByDriverIdOrderByRequestedAtAsc(UUID driverId);
}
