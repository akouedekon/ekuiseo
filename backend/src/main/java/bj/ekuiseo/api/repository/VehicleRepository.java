package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    /** Vehicules vivants d un proprietaire (suppression logique V15 : {@code deleted_at} null). */
    List<Vehicle> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    /** Tous les vehicules, supprimes compris (anonymisation : la plaque d un vehicule masque doit aussi disparaitre). */
    List<Vehicle> findByOwnerId(UUID ownerId);
}
