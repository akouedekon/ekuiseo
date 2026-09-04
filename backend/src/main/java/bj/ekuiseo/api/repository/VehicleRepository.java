package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findByOwnerId(UUID ownerId);
}
