package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Review;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.dto.review.CreateReviewRequest;
import bj.ekuiseo.api.dto.review.ReviewResponse;
import bj.ekuiseo.api.mapper.ReviewMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    private static final List<BookingStatus> REVIEWABLE_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

    private final ReviewRepository reviewRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ReviewMapper reviewMapper;
    private final NotificationService notificationService;

    public ReviewService(ReviewRepository reviewRepository, TripRepository tripRepository,
                          BookingRepository bookingRepository, UserRepository userRepository,
                          ReviewMapper reviewMapper, NotificationService notificationService) {
        this.reviewRepository = reviewRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.reviewMapper = reviewMapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public ReviewResponse createReview(UUID tripId, UUID authorId, CreateReviewRequest req) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        boolean authorIsDriver = trip.getDriver().getId().equals(authorId);
        boolean authorIsPassenger = bookingRepository.findByTripIdAndStatusIn(tripId, REVIEWABLE_STATUSES).stream()
                .anyMatch(b -> b.getPassenger().getId().equals(authorId));
        if (!authorIsDriver && !authorIsPassenger) {
            throw new ForbiddenException("Seuls le conducteur et les passagers du trajet peuvent laisser un avis");
        }
        if (authorId.equals(req.targetId())) {
            throw new BadRequestException("Vous ne pouvez pas vous evaluer vous-meme");
        }
        if (reviewRepository.existsByTripIdAndAuthorIdAndTargetId(tripId, authorId, req.targetId())) {
            throw new ConflictException("Vous avez deja evalue cette personne pour ce trajet");
        }
        User author = userRepository.findById(authorId).orElseThrow(() -> new NotFoundException("Auteur introuvable"));
        User target = userRepository.findById(req.targetId()).orElseThrow(() -> new NotFoundException("Destinataire introuvable"));

        Review review = Review.builder()
                .trip(trip)
                .author(author)
                .target(target)
                .role(req.role())
                .rating(req.rating())
                .comment(req.comment())
                .build();
        review = reviewRepository.save(review);

        recomputeRating(target);
        notificationService.notify(target, NotificationType.NEW_REVIEW,
                Map.of("tripId", tripId.toString(), "rating", String.valueOf(req.rating())));

        return reviewMapper.toResponse(review);
    }

    @Transactional(readOnly = true)
    public List<ReviewResponse> reviewsForUser(UUID userId) {
        return reviewRepository.findByTargetIdOrderByCreatedAtDesc(userId).stream()
                .map(reviewMapper::toResponse).toList();
    }

    /** Recalcule la note moyenne et le nombre d'avis d'un utilisateur (moyenne simple). */
    private void recomputeRating(User target) {
        List<Review> reviews = reviewRepository.findByTargetIdOrderByCreatedAtDesc(target.getId());
        int count = reviews.size();
        BigDecimal avg = count == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(
                        reviews.stream().mapToInt(Review::getRating).sum())
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        target.setRatingAvg(avg);
        target.setRatingCount(count);
        userRepository.save(target);
    }
}
