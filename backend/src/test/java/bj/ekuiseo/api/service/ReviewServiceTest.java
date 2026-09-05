package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F022 : cible et role deduits cote serveur, avis refuse avant le depart. */
class ReviewServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ReviewService service = new ReviewService(reviewRepository, tripRepository, bookingRepository,
            userRepository, reviewMapper, notificationService);

    private final User driver = user();
    private final User passenger = user();
    private final User stranger = user();
    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver)
                .departureAt(Instant.now().minus(2, ChronoUnit.HOURS)).build();
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        for (User u : List.of(driver, passenger, stranger)) {
            when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        }
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(reviewRepository.findByTargetIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(reviewMapper.toResponse(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            return new ReviewResponse(r.getId(), r.getTrip().getId(), r.getAuthor().getId(), r.getTarget().getId(),
                    r.getRole(), r.getRating(), r.getComment(), Instant.now());
        });
    }

    @Test
    void passenger_reviewsDriver_targetAndRoleForcedServerSide() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(eq(trip.getId()), eq(passenger.getId()), anyList()))
                .thenReturn(true);

        // Le client tente de noter un tiers avec un role fantaisiste : ignore, c est le conducteur qui est note.
        ReviewResponse response = service.createReview(trip.getId(), passenger.getId(),
                new CreateReviewRequest(stranger.getId(), ReviewRole.DRIVER, (short) 5, "Tres bien"));

        assertThat(response.targetId()).isEqualTo(driver.getId());
        assertThat(response.role()).isEqualTo(ReviewRole.PASSENGER);
        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertThat(saved.getValue().getTarget()).isSameAs(driver);
        verify(notificationService).notify(eq(driver), eq(NotificationType.NEW_REVIEW), any());
    }

    @Test
    void cashPassenger_afterDeparture_isAllowed_evenIfMarkedNoShow() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(eq(trip.getId()), eq(passenger.getId()),
                eq(List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW)))).thenReturn(true);

        ReviewResponse response = service.createReview(trip.getId(), passenger.getId(),
                new CreateReviewRequest(null, null, (short) 2, null));

        assertThat(response.targetId()).isEqualTo(driver.getId());
    }

    @Test
    void driver_reviewsPassenger_onlyIfConfirmedOnTrip() {
        Booking confirmed = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger)
                .status(BookingStatus.COMPLETED).build();
        when(bookingRepository.findByTripIdAndStatusIn(eq(trip.getId()), anyList())).thenReturn(List.of(confirmed));

        ReviewResponse ok = service.createReview(trip.getId(), driver.getId(),
                new CreateReviewRequest(passenger.getId(), null, (short) 4, null));
        assertThat(ok.role()).isEqualTo(ReviewRole.DRIVER);
        assertThat(ok.targetId()).isEqualTo(passenger.getId());

        assertThatThrownBy(() -> service.createReview(trip.getId(), driver.getId(),
                new CreateReviewRequest(stranger.getId(), null, (short) 1, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createReview(trip.getId(), driver.getId(),
                new CreateReviewRequest(null, null, (short) 1, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void stranger_isForbidden() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(any(), any(), anyList())).thenReturn(false);

        assertThatThrownBy(() -> service.createReview(trip.getId(), stranger.getId(),
                new CreateReviewRequest(driver.getId(), null, (short) 1, "diffamation")))
                .isInstanceOf(ForbiddenException.class);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void beforeDeparture_isRejected() {
        trip.setDepartureAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(any(), any(), anyList())).thenReturn(true);

        assertThatThrownBy(() -> service.createReview(trip.getId(), passenger.getId(),
                new CreateReviewRequest(driver.getId(), null, (short) 5, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("parti");
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void duplicate_isConflict() {
        when(bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(any(), any(), anyList())).thenReturn(true);
        when(reviewRepository.existsByTripIdAndAuthorIdAndTargetId(trip.getId(), passenger.getId(), driver.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createReview(trip.getId(), passenger.getId(),
                new CreateReviewRequest(null, null, (short) 5, null)))
                .isInstanceOf(ConflictException.class);
    }

    private static User user() {
        return User.builder().id(UUID.randomUUID()).firstName("A").lastName("B").phone("+2290100000000").build();
    }
}
