package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripStopRepository extends JpaRepository<TripStop, UUID> {
    List<TripStop> findByTripIdOrderByPosition(UUID tripId);

    /** Arrets de plusieurs trajets en une requete (troncon apparie d une page de recherche, TripService#enrichSegments). */
    List<TripStop> findByTripIdInOrderByPositionAsc(java.util.Collection<UUID> tripIds);
}
