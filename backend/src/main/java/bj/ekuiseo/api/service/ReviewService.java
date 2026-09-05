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
import bj.ekuiseo.api.domain.enums.ReviewRole;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avis entre conducteur et passagers d un trajet. Depuis le lot 1.4 (constat F022), la
 * cible et le role sont deduits cote serveur du lien reel entre l auteur et le trajet :
 * un passager ne peut noter que son conducteur, un conducteur que ses passagers, et
 * seulement une fois le depart passe. Le champ {@code role} du client est ignore.
 */
@Service
public class ReviewService {

    /** Statuts qui prouvent qu un passager a voyage, ou etait attendu : un absent peut noter (et etre note). */
    private static final List<BookingStatus> PASSENGER_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    /** Statuts qu un conducteur peut noter : un passager absent n est pas evalue, il est signale (BOOKING_NO_SHOW). */
    private static final List<BookingStatus> DRIVER_TARGET_STATUSES =
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
        if (trip.getDepartureAt() == null || trip.getDepartureAt().isAfter(Instant.now())) {
            throw new BadRequestException("Un avis ne peut etre laisse qu une fois le trajet parti");
        }
        UUID driverId = trip.getDriver().getId();
        boolean authorIsDriver = driverId.equals(authorId);

        UUID targetId;
        ReviewRole role;
        if (authorIsDriver) {
            if (req.targetId() == null) {
                throw new BadRequestException("Indiquez le passager a evaluer");
            }
            boolean targetIsPassenger = bookingRepository.findByTripIdAndStatusIn(tripId, DRIVER_TARGET_STATUSES).stream()
                    .map(Booking::getPassenger).map(User::getId)
                    .anyMatch(req.targetId()::equals);
            if (!targetIsPassenger) {
                throw new BadRequestException("Cette personne n a pas de reservation confirmee sur ce trajet");
            }
            targetId = req.targetId();
            role = ReviewRole.DRIVER;
        } else {
            boolean authorIsPassenger = bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(tripId, authorId, PASSENGER_STATUSES);
            if (!authorIsPassenger) {
                throw new ForbiddenException("Seuls le conducteur et les passagers du trajet peuvent laisser un avis");
            }
            // Un passager ne note que son conducteur : la cible envoyee par le client est ignoree.
            targetId = driverId;
            role = ReviewRole.PASSENGER;
        }
        if (authorId.equals(targetId)) {
            throw new BadRequestException("Vous ne pouvez pas vous evaluer vous-meme");
        }
        if (reviewRepository.existsByTripIdAndAuthorIdAndTargetId(tripId, authorId, targetId)) {
            throw new ConflictException("Vous avez deja evalue cette personne pour ce trajet");
        }
        User author = userRepository.findById(authorId).orElseThrow(() -> new NotFoundException("Auteur introuvable"));
        User target = userRepository.findById(targetId).orElseThrow(() -> new NotFoundException("Destinataire introuvable"));

        Review review = Review.builder()
                .trip(trip)
                .author(author)
                .target(target)
                .role(role)
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
