package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {
    List<Review> findByTargetIdOrderByCreatedAtDesc(UUID targetId);
    boolean existsByTripIdAndAuthorIdAndTargetId(UUID tripId, UUID authorId, UUID targetId);
}
