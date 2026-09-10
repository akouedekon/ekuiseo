package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LivePositionRequest;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Suivi en direct d un trajet (V23). Le conducteur active le partage puis envoie sa
 * position depuis son navigateur ; ses passagers confirmes la lisent sur la fiche du
 * trajet, et un lien public a jeton ({@code /live/{token}}) permet a un proche de suivre le
 * vehicule sans compte.
 * <ul>
 *   <li>Activation : conducteur du trajet, trajet PUBLISHED / FULL / ONGOING. Le jeton est
 *       genere a la premiere activation (32 octets aleatoires, base64url) et conserve tant
 *       que le trajet vit : couper puis reprendre le partage garde le meme lien.</li>
 *   <li>Positions : uniquement partage actif et trajet dans sa fenetre, d une heure avant
 *       le depart jusqu au statut COMPLETED / CANCELLED exclu. La position est horodatee
 *       cote appareil, bornee a l instant de reception (horloge en avance).</li>
 *   <li>Lecture : conducteur ou passager avec une reservation CONFIRMED, COMPLETED ou
 *       PENDING_DRIVER_APPROVAL (403 sinon) ; lecture publique par jeton, 404 des que le
 *       partage est coupe ou que le trajet est termine / annule depuis plus de 6 h.</li>
 * </ul>
 * L historique des positions est purge apres 24 h (RetentionScheduler), le jeton efface une
 * fois le trajet termine : le lien public est revocable par le conducteur (desactivation)
 * et expire de lui-meme.
 */
@Service
public class TripLiveService {

    /** Une position est acceptee au plus tot ce delai avant le depart. */
    static final Duration WINDOW_BEFORE_DEPARTURE = Duration.ofHours(1);
    /** Le lien public repond encore ce delai apres la fin ou l annulation du trajet. */
    static final Duration PUBLIC_GRACE_AFTER_END = Duration.ofHours(6);
    /** Une position datee de plus loin que cela dans le futur est ramenee a l instant de reception. */
    static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(1);

    static final List<TripStatus> SHAREABLE_STATUSES = List.of(TripStatus.PUBLISHED, TripStatus.FULL, TripStatus.ONGOING);
    static final List<BookingStatus> VIEWER_BOOKING_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.PENDING_DRIVER_APPROVAL);

    private final TripRepository tripRepository;
    private final TripPositionRepository tripPositionRepository;
    private final BookingRepository bookingRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public TripLiveService(TripRepository tripRepository, TripPositionRepository tripPositionRepository,
                           BookingRepository bookingRepository, AuditService auditService) {
        this.tripRepository = tripRepository;
        this.tripPositionRepository = tripPositionRepository;
        this.bookingRepository = bookingRepository;
        this.auditService = auditService;
    }

    /** PUT /api/v1/trips/{id}/live : active ou coupe le partage (conducteur seulement). */
    @Transactional
    public LiveSharingResponse setSharing(UUID tripId, UUID driverId, boolean enabled) {
        Trip trip = findTrip(tripId);
        requireDriver(trip, driverId);
        if (enabled && !SHAREABLE_STATUSES.contains(trip.getStatus())) {
            throw new BadRequestException("Le partage de position n est possible que sur un trajet publie ou en cours");
        }
        boolean changed = trip.isLiveSharingEnabled() != enabled;
        if (enabled && trip.getLiveShareToken() == null) {
            trip.setLiveShareToken(newToken());
        }
        trip.setLiveSharingEnabled(enabled);
        tripRepository.save(trip);
        if (changed) {
            auditService.log(driverId, enabled ? "TRIP_LIVE_SHARING_ENABLED" : "TRIP_LIVE_SHARING_DISABLED",
                    "trip", trip.getId(), Map.of());
        }
        return toSharingResponse(trip);
    }

    /** POST /api/v1/trips/{id}/live/positions : enregistre une position du conducteur (202). */
    @Transactional
    public void recordPosition(UUID tripId, UUID driverId, LivePositionRequest req) {
        recordPosition(tripId, driverId, req, Instant.now());
    }

    void recordPosition(UUID tripId, UUID driverId, LivePositionRequest req, Instant now) {
        Trip trip = findTrip(tripId);
        requireDriver(trip, driverId);
        if (!trip.isLiveSharingEnabled()) {
            throw new BadRequestException("Le partage de position n est pas active sur ce trajet");
        }
        if (!isWithinWindow(trip, now)) {
            throw new BadRequestException("La position ne peut etre partagee que d une heure avant le depart jusqu a la fin du trajet");
        }
        Instant recordedAt = req.recordedAt();
        if (recordedAt == null || recordedAt.isAfter(now.plus(MAX_CLOCK_AHEAD))) {
            recordedAt = now;
        }
        tripPositionRepository.save(TripPosition.builder()
                .trip(trip)
                .lat(req.lat())
                .lng(req.lng())
                .heading(req.heading())
                .speedKmh(req.speedKmh())
                .accuracyM(req.accuracyM())
                .recordedAt(recordedAt)
                .build());
        if (trip.getLastPositionAt() == null || recordedAt.isAfter(trip.getLastPositionAt())) {
            trip.setLastPositionAt(recordedAt);
            tripRepository.save(trip);
        }
    }

    /** GET /api/v1/trips/{id}/live : conducteur ou passager de ce trajet. */
    @Transactional(readOnly = true)
    public LivePositionResponse getLive(UUID tripId, UUID requesterId) {
        return getLive(tripId, requesterId, Instant.now());
    }

    LivePositionResponse getLive(UUID tripId, UUID requesterId, Instant now) {
        Trip trip = findTrip(tripId);
        boolean driver = trip.getDriver().getId().equals(requesterId);
        if (!driver && !bookingRepository.existsByTripIdAndPassengerIdAndStatusIn(tripId, requesterId, VIEWER_BOOKING_STATUSES)) {
            throw new ForbiddenException("Le suivi en direct est reserve au conducteur et aux passagers de ce trajet");
        }
        Optional<TripPosition> last = trip.isLiveSharingEnabled()
                ? tripPositionRepository.findFirstByTripIdOrderByRecordedAtDesc(tripId)
                : Optional.empty();
        return new LivePositionResponse(
                trip.isLiveSharingEnabled(),
                last.map(TripLiveService::toPosition).orElse(null),
                last.map(p -> staleSeconds(p, now)).orElse(null),
                trip.getStatus(),
                trip.getDepartureAt(),
                trip.isLiveSharingEnabled() ? trip.getLiveShareToken() : null);
    }

    /** GET /api/v1/live/{token} : suivi public, sans compte. 404 pour tout jeton qui ne doit plus repondre. */
    @Transactional(readOnly = true)
    public PublicLiveResponse getPublic(String token) {
        return getPublic(token, Instant.now());
    }

    PublicLiveResponse getPublic(String token, Instant now) {
        if (token == null || token.isBlank() || token.length() > 64) {
            throw new NotFoundException("Suivi introuvable");
        }
        Trip trip = tripRepository.findByLiveShareToken(token)
                .orElseThrow(() -> new NotFoundException("Suivi introuvable"));
        if (!trip.isLiveSharingEnabled() || isPublicExpired(trip, now)) {
            throw new NotFoundException("Suivi introuvable");
        }
        Optional<TripPosition> last = tripPositionRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId());
        Vehicle vehicle = trip.getVehicle();
        return new PublicLiveResponse(
                trip.getOriginLabel(), trip.getOriginLat(), trip.getOriginLng(),
                trip.getDestLabel(), trip.getDestLat(), trip.getDestLng(),
                trip.getDepartureAt(),
                trip.getStatus(),
                trip.getDriver().getFirstName(),
                vehicle == null ? null : new PublicLiveResponse.Vehicle(vehicle.getBrand(), vehicle.getModel(), vehicle.getColor()),
                last.map(TripLiveService::toPosition).orElse(null),
                last.map(p -> staleSeconds(p, now)).orElse(null));
    }

    /** Fenetre d envoi : d une heure avant le depart jusqu au statut terminal exclu. */
    static boolean isWithinWindow(Trip trip, Instant now) {
        if (!SHAREABLE_STATUSES.contains(trip.getStatus())) {
            return false;
        }
        return !now.isBefore(trip.getDepartureAt().minus(WINDOW_BEFORE_DEPARTURE));
    }

    /**
     * Le lien public ne repond plus 6 h apres la fin ou l annulation : {@code updatedAt} porte
     * l instant de la transition terminale (un trajet termine ou annule ne se modifie plus).
     */
    static boolean isPublicExpired(Trip trip, Instant now) {
        if (trip.getStatus() != TripStatus.COMPLETED && trip.getStatus() != TripStatus.CANCELLED) {
            return false;
        }
        Instant endedAt = trip.getUpdatedAt() != null ? trip.getUpdatedAt() : trip.getDepartureAt();
        return now.isAfter(endedAt.plus(PUBLIC_GRACE_AFTER_END));
    }

    static long staleSeconds(TripPosition position, Instant now) {
        return Math.max(0, Duration.between(position.getRecordedAt(), now).getSeconds());
    }

    static LivePositionResponse.Position toPosition(TripPosition p) {
        return new LivePositionResponse.Position(p.getLat(), p.getLng(), p.getHeading(), p.getSpeedKmh(),
                p.getAccuracyM(), p.getRecordedAt());
    }

    static LiveSharingResponse toSharingResponse(Trip trip) {
        String token = trip.getLiveShareToken();
        return new LiveSharingResponse(trip.isLiveSharingEnabled(), token,
                token == null ? null : "/live/" + token, trip.getLastPositionAt());
    }

    /** 32 octets aleatoires en base64url sans remplissage (43 caracteres), imprevisible et sur dans une URL. */
    String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Trip findTrip(UUID id) {
        return tripRepository.findById(id).orElseThrow(() -> new NotFoundException("Trajet introuvable"));
    }

    private static void requireDriver(Trip trip, UUID driverId) {
        if (driverId == null || !trip.getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
    }
}
