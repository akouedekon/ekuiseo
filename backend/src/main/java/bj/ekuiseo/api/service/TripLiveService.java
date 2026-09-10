package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripPosition;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LivePositionResponse;
import bj.ekuiseo.api.dto.trip.LiveSharingResponse;
import bj.ekuiseo.api.dto.trip.LiveStreamEvent;
import bj.ekuiseo.api.dto.trip.PublicLiveResponse;
import bj.ekuiseo.api.repository.TripPositionRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.service.live.LiveSessionRegistry;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Viewer;
import bj.ekuiseo.api.service.live.TripTrackingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Suivi en direct d un trajet (V23, etendu en V28). Le conducteur active le partage ; lui
 * et ses passagers confirmes envoient leur position ({@code LocationUpdateService}) ; la
 * fiche du trajet la lit par instantane ou par flux SSE ({@code TripTrackingService}), et un
 * lien public a jeton ({@code /live/{token}}) permet a un proche de suivre le vehicule sans
 * compte.
 * <ul>
 *   <li>Activation : conducteur du trajet, trajet PUBLISHED / FULL / ONGOING. Le jeton est
 *       genere a la premiere activation (32 octets aleatoires, base64url) et conserve tant
 *       que le trajet vit : couper puis reprendre le partage garde le meme lien. Couper le
 *       partage ferme les flux ouverts ({@code end SHARING_DISABLED}) et oublie la derniere
 *       position du conducteur.</li>
 *   <li>Lecture : conducteur ou passager avec une reservation CONFIRMED, COMPLETED ou
 *       PENDING_DRIVER_APPROVAL (403 sinon) ; lecture publique par jeton, limitee au
 *       conducteur, 404 des que le partage est coupe ou que le trajet est termine / annule
 *       depuis plus de 6 h.</li>
 * </ul>
 * L historique des positions est purge apres 24 h (RetentionScheduler), le jeton efface une
 * fois le trajet termine : le lien public est revocable par le conducteur (desactivation)
 * et expire de lui-meme.
 */
@Service
public class TripLiveService {

    /** Le lien public repond encore ce delai apres la fin ou l annulation du trajet. */
    static final Duration PUBLIC_GRACE_AFTER_END = Duration.ofHours(6);

    static final List<TripStatus> SHAREABLE_STATUSES = List.of(TripStatus.PUBLISHED, TripStatus.FULL, TripStatus.ONGOING);

    private final TripRepository tripRepository;
    private final TripPositionRepository tripPositionRepository;
    private final AuditService auditService;
    private final LiveSessionRegistry registry;
    private final TripTrackingService tracking;
    private final SecureRandom secureRandom = new SecureRandom();

    public TripLiveService(TripRepository tripRepository, TripPositionRepository tripPositionRepository,
                           AuditService auditService, LiveSessionRegistry registry, TripTrackingService tracking) {
        this.tripRepository = tripRepository;
        this.tripPositionRepository = tripPositionRepository;
        this.auditService = auditService;
        this.registry = registry;
        this.tracking = tracking;
    }

    /** PUT /api/v1/trips/{id}/live : active ou coupe le partage (conducteur seulement). */
    @Transactional
    public LiveSharingResponse setSharing(UUID tripId, UUID driverId, boolean enabled) {
        return setSharing(tripId, driverId, enabled, Instant.now());
    }

    LiveSharingResponse setSharing(UUID tripId, UUID driverId, boolean enabled, Instant now) {
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
            if (enabled) {
                tracking.broadcastStatus(trip, now);
            } else {
                // Les lecteurs ne doivent plus voir une position que le conducteur ne partage plus.
                registry.clearPosition(tripId, driverId);
                tracking.end(tripId, LiveStreamEvent.EndReason.SHARING_DISABLED);
            }
        }
        return toSharingResponse(trip, tracking.currentInterval(trip, now));
    }

    /** GET /api/v1/trips/{id}/live : conducteur ou passager de ce trajet. */
    @Transactional(readOnly = true)
    public LivePositionResponse getLive(UUID tripId, UUID requesterId) {
        return getLive(tripId, requesterId, Instant.now());
    }

    LivePositionResponse getLive(UUID tripId, UUID requesterId, Instant now) {
        Trip trip = findTrip(tripId);
        Viewer viewer = tracking.resolveViewer(trip, requesterId);
        Optional<LivePositionResponse.Position> last = trip.isLiveSharingEnabled() ? driverPosition(trip) : Optional.empty();
        return new LivePositionResponse(
                trip.isLiveSharingEnabled(),
                last.orElse(null),
                last.map(p -> staleSeconds(p.recordedAt(), now)).orElse(null),
                trip.getStatus(),
                trip.getDepartureAt(),
                trip.isLiveSharingEnabled() ? trip.getLiveShareToken() : null,
                tracking.currentInterval(trip, now),
                tracking.visibleParticipants(tripId, viewer),
                now);
    }

    /**
     * GET /api/v1/trips/{id}/live/stream : flux SSE pour un lecteur autorise. La transaction
     * de lecture se termine avec cette methode ; l emetteur vit ensuite sans connexion base.
     */
    @Transactional(readOnly = true)
    public SseEmitter stream(UUID tripId, UUID requesterId) {
        return stream(tripId, requesterId, Instant.now());
    }

    SseEmitter stream(UUID tripId, UUID requesterId, Instant now) {
        Trip trip = findTrip(tripId);
        Viewer viewer = tracking.resolveViewer(trip, requesterId);
        return tracking.openStream(trip, viewer, now);
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
        // Le lien public ne montre jamais un passager : conducteur seulement.
        Optional<LivePositionResponse.Position> last = driverPosition(trip);
        Vehicle vehicle = trip.getVehicle();
        return new PublicLiveResponse(
                trip.getOriginLabel(), trip.getOriginLat(), trip.getOriginLng(),
                trip.getDestLabel(), trip.getDestLat(), trip.getDestLng(),
                trip.getDepartureAt(),
                trip.getStatus(),
                trip.getDriver().getFirstName(),
                vehicle == null ? null : new PublicLiveResponse.Vehicle(vehicle.getBrand(), vehicle.getModel(), vehicle.getColor()),
                last.orElse(null),
                last.map(p -> staleSeconds(p.recordedAt(), now)).orElse(null));
    }

    /**
     * Derniere position acceptee du conducteur : le registre en memoire d abord (toujours
     * plus recent), la base en repli apres un redemarrage (au plus 30 s de retard).
     */
    private Optional<LivePositionResponse.Position> driverPosition(Trip trip) {
        Optional<LiveParticipant> live = registry.driverPosition(trip.getId());
        if (live.isPresent()) {
            return live.map(TripLiveService::toPosition);
        }
        return tripPositionRepository.findFirstByTripIdAndRoleOrderByRecordedAtDesc(trip.getId(), LiveRole.DRIVER)
                .map(TripLiveService::toPosition);
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

    static long staleSeconds(Instant recordedAt, Instant now) {
        return Math.max(0, Duration.between(recordedAt, now).getSeconds());
    }

    static LivePositionResponse.Position toPosition(TripPosition p) {
        return new LivePositionResponse.Position(p.getLat(), p.getLng(), p.getHeading(), p.getSpeedKmh(),
                p.getAccuracyM(), p.getRecordedAt());
    }

    static LivePositionResponse.Position toPosition(LiveParticipant p) {
        return new LivePositionResponse.Position(p.lat(), p.lng(), p.heading(), p.speedKmh(), p.accuracyM(), p.recordedAt());
    }

    static LiveSharingResponse toSharingResponse(Trip trip, int intervalSeconds) {
        String token = trip.getLiveShareToken();
        return new LiveSharingResponse(trip.isLiveSharingEnabled(), token,
                token == null ? null : "/live/" + token, trip.getLastPositionAt(), intervalSeconds);
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
