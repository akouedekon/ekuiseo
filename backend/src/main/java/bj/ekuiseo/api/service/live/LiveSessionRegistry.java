package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Etat en memoire du suivi en direct (contrat C, V28) : par trajet, la derniere position
 * acceptee de chaque participant et les abonnes du flux SSE. C est la source de verite
 * pour l affichage ; la base ({@code trip_positions}) n est ecrite qu au plus toutes les
 * 30 s ou tous les 200 m par participant (LocationUpdateService) et ne sert qu a la
 * reprise apres redemarrage et a l analyse des flags.
 *
 * <p>Mono-instance, comme {@code RateLimitingFilter} : l etat repart de zero a chaque
 * redeploiement (le front retombe alors sur l instantane REST puis se reabonne). Une
 * session est retiree a la fin du trajet ({@link #remove}) et par {@link #evictIdle}
 * (aucun abonne et aucune position depuis une heure), toutes les dix minutes.</p>
 */
@Component
public class LiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionRegistry.class);
    /** Une session sans abonne ni position depuis ce delai est oubliee. */
    static final long IDLE_SESSION_MILLIS = 3_600_000L;

    /** Qui lit le flux : le conducteur voit tout le monde, un passager voit le conducteur et lui-meme. */
    public record Viewer(UUID userId, LiveRole role, @Nullable UUID bookingId) {
        public boolean canSee(LiveParticipant participant) {
            if (role == LiveRole.DRIVER) {
                return true;
            }
            return participant.role() == LiveRole.DRIVER
                    || (bookingId != null && bookingId.equals(participant.bookingId()));
        }
    }

    /** Un flux SSE ouvert par un lecteur. */
    public static final class Subscriber {
        private final SseEmitter emitter;
        private final Viewer viewer;

        Subscriber(SseEmitter emitter, Viewer viewer) {
            this.emitter = emitter;
            this.viewer = viewer;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public Viewer viewer() {
            return viewer;
        }
    }

    /**
     * Etat mutable d un participant : derniere position acceptee (diffusee), derniere
     * reception (cadence de 2 s), derniere ecriture en base (economie d ecritures). Les
     * mises a jour se font sous le verrou de l objet (LocationUpdateService).
     */
    public static final class ParticipantState {
        private final UUID userId;
        @Nullable
        private volatile LiveParticipant last;
        private volatile long lastReceivedAtMillis;
        @Nullable
        private volatile Instant lastPersistedAt;
        private volatile double persistedLat;
        private volatile double persistedLng;

        ParticipantState(UUID userId) {
            this.userId = userId;
        }

        public UUID userId() {
            return userId;
        }

        @Nullable
        public LiveParticipant last() {
            return last;
        }

        public void setLast(@Nullable LiveParticipant last) {
            this.last = last;
        }

        public long lastReceivedAtMillis() {
            return lastReceivedAtMillis;
        }

        public void setLastReceivedAtMillis(long millis) {
            this.lastReceivedAtMillis = millis;
        }

        @Nullable
        public Instant lastPersistedAt() {
            return lastPersistedAt;
        }

        public double persistedLat() {
            return persistedLat;
        }

        public double persistedLng() {
            return persistedLng;
        }

        public void markPersisted(Instant at, double lat, double lng) {
            this.lastPersistedAt = at;
            this.persistedLat = lat;
            this.persistedLng = lng;
        }
    }

    private static final class TripSession {
        private final ConcurrentMap<UUID, ParticipantState> participants = new ConcurrentHashMap<>();
        private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
        private volatile long lastActivityMillis;

        TripSession(long nowMillis) {
            this.lastActivityMillis = nowMillis;
        }
    }

    private final ConcurrentMap<UUID, TripSession> sessions = new ConcurrentHashMap<>();

    /** Etat du participant, cree a la premiere position. */
    public ParticipantState participant(UUID tripId, UUID userId, long nowMillis) {
        TripSession session = sessions.computeIfAbsent(tripId, id -> new TripSession(nowMillis));
        session.lastActivityMillis = nowMillis;
        return session.participants.computeIfAbsent(userId, ParticipantState::new);
    }

    public Optional<ParticipantState> findParticipant(UUID tripId, UUID userId) {
        TripSession session = sessions.get(tripId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.participants.get(userId));
    }

    /** Dernieres positions acceptees de tous les participants du trajet (ordre indifferent). */
    public List<LiveParticipant> lastPositions(UUID tripId) {
        TripSession session = sessions.get(tripId);
        if (session == null) {
            return List.of();
        }
        List<LiveParticipant> out = new ArrayList<>();
        for (ParticipantState state : session.participants.values()) {
            LiveParticipant last = state.last;
            if (last != null) {
                out.add(last);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Derniere position acceptee du conducteur, s il partage. */
    public Optional<LiveParticipant> driverPosition(UUID tripId) {
        return lastPositions(tripId).stream().filter(p -> p.role() == LiveRole.DRIVER).findFirst();
    }

    /** Oublie la derniere position d un participant (partage coupe), sans toucher a la cadence. */
    public void clearPosition(UUID tripId, UUID userId) {
        findParticipant(tripId, userId).ifPresent(state -> state.setLast(null));
    }

    public Subscriber subscribe(UUID tripId, SseEmitter emitter, Viewer viewer, long nowMillis) {
        TripSession session = sessions.computeIfAbsent(tripId, id -> new TripSession(nowMillis));
        session.lastActivityMillis = nowMillis;
        Subscriber subscriber = new Subscriber(emitter, viewer);
        session.subscribers.add(subscriber);
        return subscriber;
    }

    public void unsubscribe(UUID tripId, Subscriber subscriber) {
        TripSession session = sessions.get(tripId);
        if (session != null) {
            session.subscribers.remove(subscriber);
        }
    }

    /** Copie defensive : la diffusion itere pendant que des abonnes arrivent ou partent. */
    public Collection<Subscriber> subscribers(UUID tripId) {
        TripSession session = sessions.get(tripId);
        return session == null ? List.of() : List.copyOf(session.subscribers);
    }

    public int subscriberCount(UUID tripId) {
        TripSession session = sessions.get(tripId);
        return session == null ? 0 : session.subscribers.size();
    }

    /** Trajets ayant au moins un abonne : ceux dont le battement doit verifier l etat. */
    public Set<UUID> tripsWithSubscribers() {
        Set<UUID> out = ConcurrentHashMap.newKeySet();
        sessions.forEach((tripId, session) -> {
            if (!session.subscribers.isEmpty()) {
                out.add(tripId);
            }
        });
        return out;
    }

    public int sessionCount() {
        return sessions.size();
    }

    public boolean hasSession(UUID tripId) {
        return sessions.containsKey(tripId);
    }

    /** Retire la session et rend ses abonnes, a fermer par l appelant (fin du trajet). */
    public Collection<Subscriber> remove(UUID tripId) {
        TripSession session = sessions.remove(tripId);
        return session == null ? List.of() : List.copyOf(session.subscribers);
    }

    /** Sessions sans abonne et sans activite depuis {@code idleMillis} : oubliees. */
    public int evictIdle(long idleMillis, long nowMillis) {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().subscribers.isEmpty()
                && nowMillis - e.getValue().lastActivityMillis > idleMillis);
        return before - sessions.size();
    }

    @Scheduled(fixedRate = 600_000, initialDelay = 600_000)
    public void cleanup() {
        try {
            int evicted = evictIdle(IDLE_SESSION_MILLIS, System.currentTimeMillis());
            if (evicted > 0) {
                log.info("Suivi en direct : {} session(s) inactive(s) oubliee(s)", evicted);
            }
        } catch (RuntimeException ex) {
            log.error("Purge des sessions de suivi en direct : echec de l execution", ex);
        }
    }

    @Override
    public String toString() {
        return "LiveSessionRegistry{sessions=" + sessions.size() + '}';
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sessions);
    }
}
