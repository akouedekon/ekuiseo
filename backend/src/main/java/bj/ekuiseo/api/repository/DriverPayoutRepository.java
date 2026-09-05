package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, UUID> {
    List<DriverPayout> findByDriverIdOrderByRequestedAtDesc(UUID driverId);
    /** Reversements non soldes d un conducteur : bloquent l anonymisation (UserService#anonymize). */
    boolean existsByDriverIdAndStatusIn(UUID driverId, java.util.List<bj.ekuiseo.api.domain.enums.PayoutStatus> statuses);
}
