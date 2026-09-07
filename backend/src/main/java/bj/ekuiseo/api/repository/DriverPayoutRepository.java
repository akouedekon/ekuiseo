package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayout;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, UUID> {
    List<DriverPayout> findByDriverIdOrderByRequestedAtDesc(UUID driverId);

    /** Vue back-office : conducteur charge avec le lot (constats F119/F308), du plus recent au plus ancien. */
    @EntityGraph(attributePaths = "driver")
    @Query("select p from DriverPayout p order by p.requestedAt desc")
    List<DriverPayout> findAllWithDriver();
    /** Reversements non soldes d un conducteur : bloquent l anonymisation (UserService#anonymize). */
    boolean existsByDriverIdAndStatusIn(UUID driverId, java.util.List<bj.ekuiseo.api.domain.enums.PayoutStatus> statuses);
}
