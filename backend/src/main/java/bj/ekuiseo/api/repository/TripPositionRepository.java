package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.enums.LiveRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TripPositionRepository extends JpaRepository<TripPosition, UUID> {

    /**
     * Derniere position du conducteur d un trajet (index (trip_id, recorded_at desc), V23).
     * Depuis V28 la table contient aussi les positions des passagers : cette requete filtre
     * sur le role, c est elle qui alimente le lien public.
     */
    Optional<TripPosition> findFirstByTripIdAndRoleOrderByRecordedAtDesc(UUID tripId, LiveRole role);

    /** Derniere position d un participant donne (index (trip_id, user_id, recorded_at desc), V28). */
    Optional<TripPosition> findFirstByTripIdAndUserIdOrderByRecordedAtDesc(UUID tripId, UUID userId);

    /** Purge nocturne (RetentionScheduler) : positions plus vieilles que la retention (24 h). */
    @Modifying
    @Query("delete from TripPosition p where p.recordedAt < :before")
    int deleteByRecordedAtBefore(@Param("before") Instant before);
}
