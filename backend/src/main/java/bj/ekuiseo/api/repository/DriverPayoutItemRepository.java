package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayoutItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverPayoutItemRepository extends JpaRepository<DriverPayoutItem, UUID> {
    List<DriverPayoutItem> findByPayoutId(UUID payoutId);

    /** Nombre de reservations incluses dans un lot (utilise comme "tripCount" cote admin, voir AdminPayoutResponse). */
    long countByPayoutId(UUID payoutId);
}
