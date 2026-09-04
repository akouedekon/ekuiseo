package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripStopRepository extends JpaRepository<TripStop, UUID> {
    List<TripStop> findByTripIdOrderByPosition(UUID tripId);
}
