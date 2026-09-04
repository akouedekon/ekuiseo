package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutRepository extends JpaRepository<DriverPayout, UUID> {
    List<DriverPayout> findByDriverIdOrderByRequestedAtDesc(UUID driverId);
}
