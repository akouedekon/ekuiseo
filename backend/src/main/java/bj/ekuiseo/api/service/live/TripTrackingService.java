package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.config.AsyncConfig;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.dto.trip.LiveStreamEvent;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.service.NotificationService;
import bj.ekuiseo.api.service.NotificationTemplates;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Subscriber;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Viewer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Lecture du suivi en direct (contrat C, V28) : qui voit quoi, a quelle cadence, et par
 * quel canal.
 * <ul>
 *   <li><b>Visibilite</b> : le conducteur voit chaque passager confirme qui partage ; un
 *       passager (reservation CONFIRMED, COMPLETED ou PENDING_DRIVER_APPROVAL) voit le
 *       conducteur et lui-meme ; le lien public ne voit que le conducteur (TripLiveService) ;
 *       toute autre personne recoit 403.</li>
 *   <li><b>Cadence recommandee</b> ({@code intervalSeconds}) : 30 s avant le depart, 15 s
 *       pendant, 5 s des que le conducteur est a moins de 3 km d un point de prise en charge
 *       d un passager confirme.</li>
 *   <li><b>Flux SSE</b> ({@code GET /trips/{id}/live/stream}) : instantane a l ouverture,
 *       puis chaque position acceptee visible par l abonne, les changements d etat, un
 *       battement {@code ping} toutes les 20 s, et {@code end} quand le trajet se termine,
 *       est annule ou que le conducteur coupe le partage. La diffusion se fait apres
 *       validation de la transaction, sur l executeur {@code liveExecutor} : jamais dans le
 *       fil de la requete du conducteur.</li>
 *   <li><b>Notifications d approche</b> : DRIVER_NEARBY (moins de 1 km du point de prise en
 *       charge) et DRIVER_ARRIVED (moins de 150 m), une seule fois chacune par reservation,
 *       horodatees sur la reservation ; push et in-app seulement.</li>
 * </ul>
 * Aucune coordonnee n est jamais journalisee.
 */
@Service
public class TripTrackingService {

    private static final Logger log = LoggerFactory.getLogger(TripTrackingService.class);

    static final List<BookingStatus> VIEWER_BOOKING_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.PENDING_DRIVER_APPROVAL);

    static final int INTERVAL_BEFORE_DEPARTURE = 30;
    static final int INTERVAL_DURING_TRIP = 15;
    static final int INTERVAL_NEAR_PICKUP = 5;
    /** En deca de cette distance d un point de prise en charge, la cadence passe a 5 s. */
    static final double NEAR_PICKUP_METERS = 3_000d;
    /** DRIVER_NEARBY : le conducteur est a moins de 1 km du point de prise en charge du passager. */
    static final double NEARBY_METERS = 1_000d;
    /** DRIVER_ARRIVED : moins de 150 m. */
    static final double ARRIVED_METERS = 150d;

    private final LiveSessionRegistry registry;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final TripStopRepository tripStopRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final TransactionTemplate transaction;
    private final long streamTimeoutMillis;

    @Autowired
    public TripTrackingService(LiveSessionRegistry registry, TripRepository tripRepository,
                               BookingRepository bookingRepository, TripStopRepository tripStopRepository,
                               NotificationService notificationService, ObjectMapper objectMapper,
                               @Qualifier(AsyncConfig.LIVE_EXECUTOR) Executor executor,
                               PlatformTransactionManager transactionManager,
                               @Value("${ekuiseo.live.stream-timeout-minutes:30}") long streamTimeoutMinutes) {
        this.registry = registry;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.tripStopRepository = tripStopRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.streamTimeoutMillis = Duration.ofMinutes(streamTimeoutMinutes).toMillis();
    }

    /* ---------------------------------------------------------------- droits */

    /** Le conducteur, ou un passager avec une reservation active sur ce trajet ; 403 sinon. */
    public Viewer resolveViewer(Trip trip, UUID userId) {
        if (userId == null) {
            throw new ForbiddenException("Le suivi en direct est reserve au conducteur et aux passagers de ce trajet");
        }
        if (trip.getDriver().getId().equals(userId)) {
            return new Viewer(userId, LiveRole.DRIVER, null);
        }
        List<Booking> bookings = bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(
                trip.getId(), userId, VIEWER_BOOKING_STATUSES);
        if (bookings.isEmpty()) {
            throw new ForbiddenException("Le suivi en direct est reserve au conducteur et aux passagers de ce trajet");
        }
        return new Viewer(userId, LiveRole.PASSENGER, bookings.get(0).getId());
    }

    /** Positions du registre que ce lecteur a le droit de voir. */
    public List<LiveParticipant> visibleParticipants(UUID tripId, Viewer viewer) {
        List<LiveParticipant> out = new ArrayList<>();
        for (LiveParticipant participant : registry.lastPositions(tripId)) {
            if (viewer.canSee(participant)) {
                out.add(participant);
            }
        }
        return out;
    }

    /* --------------------------------------------------------------- cadence */

    /** Points de prise en charge des passagers confirmes (origine du trajet ou arret de montee). */
    public List<PickupPoint> pickupPoints(Trip trip) {
        List<Booking> confirmed = bookingRepository.findByTripIdAndStatusIn(trip.getId(), List.of(BookingStatus.CONFIRMED));
        if (confirmed.isEmpty()) {
            return List.of();
        }
        boolean anyStop = confirmed.stream().anyMatch(b -> b.getPickupStopId() != null);
        List<TripStop> stops = anyStop ? tripStopRepository.findByTripIdOrderByPosition(trip.getId()) : List.of();
        List<PickupPoint> points = new ArrayList<>(confirmed.size());
        for (Booking booking : confirmed) {
            double lat = trip.getOriginLat();
            double lng = trip.getOriginLng();
            if (booking.getPickupStopId() != null) {
                Optional<TripStop> stop = stops.stream().filter(s -> s.getId().equals(booking.getPickupStopId())).findFirst();
                if (stop.isPresent()) {
                    lat = stop.get().getLat();
                    lng = stop.get().getLng();
                }
            }
            points.add(new PickupPoint(booking, lat, lng));
        }
        return points;
    }

    /**
     * Cadence recommandee : 30 s avant le depart, 15 s pendant, 5 s quand le conducteur est a
     * moins de 3 km d un point de prise en charge. Sans position du conducteur, la cadence ne
     * depend que de l heure.
     */
    public int intervalSeconds(Trip trip, LiveParticipant driver, List<PickupPoint> pickups, Instant now) {
        if (driver != null) {
            for (PickupPoint pickup : pickups) {
                if (GeoMath.distanceMeters(driver.lat(), driver.lng(), pickup.lat(), pickup.lng()) < NEAR_PICKUP_METERS) {
                    return INTERVAL_NEAR_PICKUP;
                }
            }
        }
        boolean started = trip.getStatus() == TripStatus.ONGOING || !now.isBefore(trip.getDepartureAt());
        return started ? INTERVAL_DURING_TRIP : INTERVAL_BEFORE_DEPARTURE;
    }

    /** Cadence courante d un trajet, a partir du registre (PUT /live, GET /live). */
    public int currentInterval(Trip trip, Instant now) {
        LiveParticipant driver = registry.driverPosition(trip.getId()).orElse(null);
        return intervalSeconds(trip, driver, pickupPoints(trip), now);
    }

    /* ------------------------------------------------------- notifications */

    /**
     * Notifications d approche apres une position acceptee du conducteur : une seule fois
     * chacune par reservation, horodatees sur la reservation dans la transaction courante ;
     * les canaux sortants partent apres validation (NotificationService). A moins de 150 m,
     * DRIVER_ARRIVED remplace un DRIVER_NEARBY jamais envoye : le passager ne recoit pas deux
     * notifications a la suite.
     */
    public int notifyApproach(Trip trip, LiveParticipant driver, List<PickupPoint> pickups, Instant now) {
        int sent = 0;
        for (PickupPoint pickup : pickups) {
            Booking booking = pickup.booking();
            if (booking.getDriverArrivedNotifiedAt() != null) {
                continue;
            }
            double meters = GeoMath.distanceMeters(driver.lat(), driver.lng(), pickup.lat(), pickup.lng());
            if (meters < ARRIVED_METERS) {
                booking.setDriverArrivedNotifiedAt(now);
                if (booking.getDriverNearbyNotifiedAt() == null) {
                    booking.setDriverNearbyNotifiedAt(now);
                }
                bookingRepository.save(booking);
                notificationService.notifyPushOnly(booking.getPassenger(), NotificationType.DRIVER_ARRIVED,
                        approachPayload(trip, booking, driver, meters));
                sent++;
            } else if (meters < NEARBY_METERS && booking.getDriverNearbyNotifiedAt() == null) {
                booking.setDriverNearbyNotifiedAt(now);
                bookingRepository.save(booking);
                notificationService.notifyPushOnly(booking.getPassenger(), NotificationType.DRIVER_NEARBY,
                        approachPayload(trip, booking, driver, meters));
                sent++;
            }
        }
        return sent;
    }

    private static Map<String, Object> approachPayload(Trip trip, Booking booking, LiveParticipant driver, double meters) {
        return NotificationTemplates.payload(
                "bookingId", booking.getId().toString(),
                "tripId", trip.getId().toString(),
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "driverFirstName", driver.firstName(),
                "distanceM", (long) Math.round(meters),
                "forPassenger", true);
    }

    /* ------------------------------------------------------------- flux SSE */

    /**
     * Ouvre un flux pour un lecteur autorise : l instantane part immediatement (mis en
     * tampon par l emetteur jusqu a ce que la reponse soit engagee), puis les evenements
     * diffuses. L abonne est retire a la fermeture, au delai ou a l erreur : aucun emetteur
     * ne survit a son client.
     */
    public SseEmitter openStream(Trip trip, Viewer viewer, Instant now) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        Subscriber subscriber = registry.subscribe(trip.getId(), emitter, viewer, now.toEpochMilli());
        Runnable release = () -> registry.unsubscribe(trip.getId(), subscriber);
        emitter.onCompletion(release);
        emitter.onTimeout(() -> {
            release.run();
            emitter.complete();
        });
        emitter.onError(ex -> release.run());
        LiveStreamEvent.Snapshot snapshot = LiveStreamEvent.Snapshot.of(
                trip.getStatus(), trip.isLiveSharingEnabled(), currentInterval(trip, now),
                visibleParticipants(trip.getId(), viewer), now);
        if (!send(subscriber, "snapshot", snapshot)) {
            registry.unsubscribe(trip.getId(), subscriber);
        }
        return emitter;
    }

    /** Diffuse une position acceptee aux abonnes qui ont le droit de la voir, apres commit et hors du fil appelant. */
    public void broadcastPosition(UUID tripId, LiveParticipant participant) {
        afterCommit(() -> {
            LiveStreamEvent.Position event = LiveStreamEvent.Position.of(participant);
            for (Subscriber subscriber : registry.subscribers(tripId)) {
                if (subscriber.viewer().canSee(participant)) {
                    send(subscriber, "position", event);
                }
            }
        });
    }

    /** Changement d etat du trajet ou du partage : a tous les abonnes. */
    public void broadcastStatus(Trip trip, Instant now) {
        UUID tripId = trip.getId();
        LiveStreamEvent.Status event = LiveStreamEvent.Status.of(trip.getStatus(), trip.isLiveSharingEnabled(),
                currentInterval(trip, now));
        afterCommit(() -> {
            for (Subscriber subscriber : registry.subscribers(tripId)) {
                send(subscriber, "status", event);
            }
        });
    }

    /** Fin du flux : evenement {@code end} a chaque abonne, emetteurs fermes, session oubliee. */
    public void end(UUID tripId, LiveStreamEvent.EndReason reason) {
        afterCommit(() -> endNow(tripId, reason));
    }

    void endNow(UUID tripId, LiveStreamEvent.EndReason reason) {
        LiveStreamEvent.End event = LiveStreamEvent.End.of(reason);
        for (Subscriber subscriber : registry.remove(tripId)) {
            send(subscriber, "end", event);
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException ignored) {
                // Deja ferme cote client.
            }
        }
    }

    /**
     * Battement toutes les 20 s (ekuiseo.live.ping-seconds) : un {@code ping} a chaque abonne
     * (les connexions mortes sont detectees a l ecriture), et verification de l etat des
     * trajets suivis, pour fermer les flux d un trajet termine ou annule par le scheduler
     * de cycle de vie ou par le conducteur.
     */
    @Scheduled(fixedRateString = "${ekuiseo.live.ping-seconds:20}000", initialDelayString = "${ekuiseo.live.ping-seconds:20}000")
    public void heartbeat() {
        try {
            heartbeat(Instant.now());
        } catch (RuntimeException ex) {
            log.error("Battement du suivi en direct : echec de l execution", ex);
        }
    }

    int heartbeat(Instant now) {
        int pinged = 0;
        for (UUID tripId : registry.tripsWithSubscribers()) {
            Optional<TripStatus> status = transaction.execute(tx -> tripRepository.findById(tripId).map(Trip::getStatus));
            if (status == null || status.isEmpty() || status.get() == TripStatus.COMPLETED) {
                endNow(tripId, LiveStreamEvent.EndReason.TRIP_COMPLETED);
                continue;
            }
            if (status.get() == TripStatus.CANCELLED) {
                endNow(tripId, LiveStreamEvent.EndReason.TRIP_CANCELLED);
                continue;
            }
            for (Subscriber subscriber : registry.subscribers(tripId)) {
                if (sendRaw(subscriber, SseEmitter.event().name("ping").data(String.valueOf(now.getEpochSecond())))) {
                    pinged++;
                } else {
                    registry.unsubscribe(tripId, subscriber);
                }
            }
        }
        return pinged;
    }

    /* ------------------------------------------------------------- interne */

    private boolean send(Subscriber subscriber, String name, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Evenement {} du suivi en direct non serialisable", name, ex);
            return false;
        }
        return sendRaw(subscriber, SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON));
    }

    /** Ecrit sur l emetteur ; faux (et emetteur ferme) si le client est parti. */
    private static boolean sendRaw(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
            return true;
        } catch (IOException | IllegalStateException ex) {
            try {
                subscriber.emitter().completeWithError(ex);
            } catch (RuntimeException ignored) {
                // Deja ferme.
            }
            return false;
        }
    }

    /** Apres validation de la transaction courante s il y en a une, sinon tout de suite ; toujours sur l executeur dedie. */
    private void afterCommit(Runnable action) {
        Runnable dispatch = () -> {
            try {
                executor.execute(action);
            } catch (RuntimeException ex) {
                log.warn("Diffusion du suivi en direct non planifiee", ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }
}
