package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.TripPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TripPositionRepository extends JpaRepository<TripPosition, UUID> {

    /** Derniere position connue d un trajet (index (trip_id, recorded_at desc), V23). */
    Optional<TripPosition> findFirstByTripIdOrderByRecordedAtDesc(UUID tripId);

    /** Purge nocturne (RetentionScheduler) : positions plus vieilles que la retention (24 h). */
    @Modifying
    @Query("delete from TripPosition p where p.recordedAt < :before")
    int deleteByRecordedAtBefore(@Param("before") Instant before);
}
