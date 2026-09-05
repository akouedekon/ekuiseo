package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.DriverPayoutItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverPayoutItemRepository extends JpaRepository<DriverPayoutItem, UUID> {
    List<DriverPayoutItem> findByPayoutId(UUID payoutId);

    /** Nombre de reservations incluses dans un lot (utilise comme "tripCount" cote admin, voir AdminPayoutResponse). */
    long countByPayoutId(UUID payoutId);

    /** Une reservation figure au plus dans un lot (contrainte unique en base). */
    Optional<DriverPayoutItem> findByBookingId(UUID bookingId);

    /** Reservations remboursees apres inclusion dans un lot deja traite : a deduire du prochain virement. */
    long countByPayoutIdAndReversedAtIsNotNull(UUID payoutId);

    @Query("select coalesce(sum(i.netAmount), 0L) from DriverPayoutItem i where i.payout.id = :payoutId and i.reversedAt is not null")
    long sumReversedByPayoutId(@Param("payoutId") UUID payoutId);
}
