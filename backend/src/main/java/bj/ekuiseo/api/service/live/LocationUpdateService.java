package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LivePositionAck;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.ParticipantState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reception d une position ({@code POST /api/v1/trips/{id}/live/positions}, contrat C, V28),
 * du conducteur ou d un passager confirme. Validation dans l ordre :
 * <ol>
 *   <li>participant autorise : conducteur du trajet (partage active) ou passager avec une
 *       reservation CONFIRMED ; 403 sinon ;</li>
 *   <li>trajet dans sa fenetre : d une heure avant le depart jusqu au statut COMPLETED /
 *       CANCELLED exclu ; 400 sinon ;</li>
 *   <li>cadence : au moins 2 s depuis la derniere position du meme participant, sinon 429 ;</li>
 *   <li>zone : latitude 5,0-13,0 et longitude 0,0-4,5 (Benin et marges), sinon flag
 *       {@code OUT_OF_AREA}, position non diffusee ;</li>
 *   <li>horloge : {@code recordedAt} a plus de 2 min de l heure serveur -> flag {@code CLOCK_SKEW}
 *       et heure serveur retenue (absent : heure serveur, sans flag) ;</li>
 *   <li>teleportation : vitesse impliquee depuis la derniere position acceptee superieure a
 *       200 km/h -> flag {@code TELEPORT}, non diffusee ;</li>
 *   <li>precision : {@code accuracyM} superieure a 500 m -> flag {@code LOW_ACCURACY}, diffusee
 *       avec le flag.</li>
 * </ol>
 * Les flags sont conserves en base pour analyse ; aucun blocage automatique n en decoule.
 * Persistance : une position sans flag n est ecrite qu au plus toutes les 30 s ou tous les
 * 200 m par participant (la memoire fait foi, LiveSessionRegistry) ; une position signalee
 * est toujours ecrite, bornee par la cadence de 2 s et le quota {@code live:}.
 */
@Service
public class LocationUpdateService {

    static final Duration WINDOW_BEFORE_DEPARTURE = Duration.ofHours(1);
    static final List<TripStatus> SHAREABLE_STATUSES = List.of(TripStatus.PUBLISHED, TripStatus.FULL, TripStatus.ONGOING);

    static final long MIN_INTERVAL_MILLIS = 2_000L;
    static final double MIN_LAT = 5.0;
    static final double MAX_LAT = 13.0;
    static final double MIN_LNG = 0.0;
    static final double MAX_LNG = 4.5;
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);
    static final double MAX_SPEED_KMH = 200d;
    static final float MAX_ACCURACY_M = 500f;
    static final Duration PERSIST_MIN_INTERVAL = Duration.ofSeconds(30);
    static final double PERSIST_MIN_DISTANCE_M = 200d;

    public static final String FLAG_OUT_OF_AREA = "OUT_OF_AREA";
    public static final String FLAG_CLOCK_SKEW = "CLOCK_SKEW";
    public static final String FLAG_TELEPORT = "TELEPORT";
    public static final String FLAG_LOW_ACCURACY = "LOW_ACCURACY";

    private final TripRepository tripRepository;
    private final TripPositionRepository tripPositionRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final LiveSessionRegistry registry;
    private final TripTrackingService tracking;

    public LocationUpdateService(TripRepository tripRepository, TripPositionRepository tripPositionRepository,
                                 BookingRepository bookingRepository, UserRepository userRepository,
                                 LiveSessionRegistry registry, TripTrackingService tracking) {
        this.tripRepository = tripRepository;
        this.tripPositionRepository = tripPositionRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.registry = registry;
        this.tracking = tracking;
    }

    @Transactional
    public LivePositionAck record(UUID tripId, UUID userId, LivePositionRequest req) {
        return record(tripId, userId, req, Instant.now());
    }

    LivePositionAck record(UUID tripId, UUID userId, LivePositionRequest req, Instant now) {
        Trip trip = tripRepository.findById(tripId).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
        Participant participant = resolveParticipant(trip, userId);
        if (participant.role() == LiveRole.DRIVER && !trip.isLiveSharingEnabled()) {
            throw new BadRequestException("Le partage de position n est pas active sur ce trajet");
        }
        if (!isWithinWindow(trip, now)) {
            throw new BadRequestException("La position ne peut etre partagee que d une heure avant le depart jusqu a la fin du trajet");
        }

        ParticipantState state = registry.participant(tripId, userId, now.toEpochMilli());
        List<PickupPoint> pickups = tracking.pickupPoints(trip);
        Outcome outcome;
        LiveParticipant position;
        // Le verrou par participant rend atomiques la cadence, la comparaison avec la
        // derniere position acceptee et l economie d ecritures ; il ne couvre ni la
        // diffusion (apres commit, autre fil) ni les notifications.
        synchronized (state) {
            long sinceLast = now.toEpochMilli() - state.lastReceivedAtMillis();
            if (state.lastReceivedAtMillis() > 0 && sinceLast < MIN_INTERVAL_MILLIS) {
                throw new TooManyRequestsException("Une position toutes les 2 secondes au plus", 1);
            }
            state.setLastReceivedAtMillis(now.toEpochMilli());
            outcome = validate(state, req, now);
            position = new LiveParticipant(participant.role(), participant.bookingId(),
                    participant.firstName(), req.lat(), req.lng(), req.heading(), req.speedKmh(), req.accuracyM(),
                    outcome.recordedAt(), outcome.flags());
            if (outcome.accepted()) {
                state.setLast(position);
            }
            if (shouldPersist(state, outcome, req, now)) {
                tripPositionRepository.save(TripPosition.builder()
                        .trip(trip)
                        .userId(userId)
                        .role(participant.role())
                        .lat(req.lat())
                        .lng(req.lng())
                        .heading(req.heading())
                        .speedKmh(req.speedKmh())
                        .accuracyM(req.accuracyM())
                        .recordedAt(outcome.recordedAt())
                        .flags(outcome.flags().isEmpty() ? null : String.join(",", outcome.flags()))
                        .build());
                if (outcome.accepted()) {
                    state.markPersisted(now, req.lat(), req.lng());
                }
            }
        }
        if (outcome.accepted()) {
            tracking.broadcastPosition(tripId, position);
            if (participant.role() == LiveRole.DRIVER) {
                if (trip.getLastPositionAt() == null || outcome.recordedAt().isAfter(trip.getLastPositionAt())) {
                    trip.setLastPositionAt(outcome.recordedAt());
                    tripRepository.save(trip);
                }
                tracking.notifyApproach(trip, position, pickups, now);
            }
        }
        LiveParticipant driver = registry.driverPosition(tripId).orElse(null);
        int interval = tracking.intervalSeconds(trip, driver, pickups, now);
        return new LivePositionAck(outcome.accepted(), outcome.flags(), interval);
    }

    /* ---------------------------------------------------------------- regles */

    record Participant(LiveRole role, UUID bookingId, String firstName) {
    }

    record Outcome(boolean accepted, List<String> flags, Instant recordedAt) {
    }

    private Participant resolveParticipant(Trip trip, UUID userId) {
        if (userId == null) {
            throw new ForbiddenException("Seuls le conducteur et les passagers confirmes de ce trajet peuvent partager leur position");
        }
        User driver = trip.getDriver();
        if (driver.getId().equals(userId)) {
            return new Participant(LiveRole.DRIVER, null, driver.getFirstName());
        }
        List<Booking> confirmed = bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(
                trip.getId(), userId, List.of(BookingStatus.CONFIRMED));
        if (confirmed.isEmpty()) {
            throw new ForbiddenException("Seuls le conducteur et les passagers confirmes de ce trajet peuvent partager leur position");
        }
        Booking booking = confirmed.get(0);
        String firstName = booking.getPassenger() != null ? booking.getPassenger().getFirstName()
                : userRepository.findById(userId).map(User::getFirstName).orElse("");
        return new Participant(LiveRole.PASSENGER, booking.getId(), firstName);
    }

    /** Fenetre d envoi : d une heure avant le depart jusqu au statut terminal exclu. */
    static boolean isWithinWindow(Trip trip, Instant now) {
        if (!SHAREABLE_STATUSES.contains(trip.getStatus())) {
            return false;
        }
        return !now.isBefore(trip.getDepartureAt().minus(WINDOW_BEFORE_DEPARTURE));
    }

    /** Zone, horloge, teleportation, precision : flags et decision de diffusion. */
    static Outcome validate(ParticipantState state, LivePositionRequest req, Instant now) {
        List<String> flags = new ArrayList<>(3);
        boolean accepted = true;

        if (req.lat() < MIN_LAT || req.lat() > MAX_LAT || req.lng() < MIN_LNG || req.lng() > MAX_LNG) {
            flags.add(FLAG_OUT_OF_AREA);
            accepted = false;
        }

        Instant recordedAt = req.recordedAt();
        if (recordedAt == null) {
            recordedAt = now;
        } else if (Duration.between(recordedAt, now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            flags.add(FLAG_CLOCK_SKEW);
            recordedAt = now;
        }

        LiveParticipant previous = state.last();
        if (accepted && previous != null) {
            double meters = GeoMath.distanceMeters(previous.lat(), previous.lng(), req.lat(), req.lng());
            long millis = Duration.between(previous.recordedAt(), recordedAt).toMillis();
            if (GeoMath.impliedSpeedKmh(meters, millis) > MAX_SPEED_KMH) {
                flags.add(FLAG_TELEPORT);
                accepted = false;
            }
        }

        if (req.accuracyM() != null && req.accuracyM() > MAX_ACCURACY_M) {
            flags.add(FLAG_LOW_ACCURACY);
        }
        return new Outcome(accepted, List.copyOf(flags), recordedAt);
    }

    /** Toujours pour une position signalee ; sinon au plus toutes les 30 s ou tous les 200 m. */
    static boolean shouldPersist(ParticipantState state, Outcome outcome, LivePositionRequest req, Instant now) {
        if (!outcome.flags().isEmpty()) {
            return true;
        }
        Instant lastPersisted = state.lastPersistedAt();
        if (lastPersisted == null) {
            return true;
        }
        if (Duration.between(lastPersisted, now).compareTo(PERSIST_MIN_INTERVAL) >= 0) {
            return true;
        }
        return GeoMath.distanceMeters(state.persistedLat(), state.persistedLng(), req.lat(), req.lng()) >= PERSIST_MIN_DISTANCE_M;
    }
}
