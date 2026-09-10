package bj.ekuiseo.api.service.live;

import bj.ekuiseo.api.domain.enums.LiveRole;
import bj.ekuiseo.api.dto.trip.LiveParticipant;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Subscriber;
import bj.ekuiseo.api.service.live.LiveSessionRegistry.Viewer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Registre en memoire du suivi en direct (V28) : positions par participant, abonnes, fin de trajet, purge. */
class LiveSessionRegistryTest {

    private final LiveSessionRegistry registry = new LiveSessionRegistry();
    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID passengerId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final long now = Instant.parse("2026-09-10T08:00:00Z").toEpochMilli();

    @Test
    void keepsTheLastAcceptedPositionPerParticipant() {
        assertThat(registry.lastPositions(tripId)).isEmpty();
        assertThat(registry.driverPosition(tripId)).isEmpty();

        registry.participant(tripId, driverId, now).setLast(participant(LiveRole.DRIVER, null, 6.40));
        registry.participant(tripId, passengerId, now).setLast(participant(LiveRole.PASSENGER, bookingId, 6.38));
        registry.participant(tripId, driverId, now + 1000).setLast(participant(LiveRole.DRIVER, null, 6.41));

        assertThat(registry.lastPositions(tripId)).hasSize(2);
        assertThat(registry.driverPosition(tripId).orElseThrow().lat()).isEqualTo(6.41);
        assertThat(registry.findParticipant(tripId, passengerId)).isPresent();
        assertThat(registry.findParticipant(UUID.randomUUID(), passengerId)).isEmpty();

        registry.clearPosition(tripId, driverId);
        assertThat(registry.driverPosition(tripId)).isEmpty();
        assertThat(registry.lastPositions(tripId)).hasSize(1);
    }

    @Test
    void viewer_visibility_followsTheContract() {
        LiveParticipant driver = participant(LiveRole.DRIVER, null, 6.40);
        LiveParticipant me = participant(LiveRole.PASSENGER, bookingId, 6.38);
        LiveParticipant other = participant(LiveRole.PASSENGER, UUID.randomUUID(), 6.39);

        Viewer asDriver = new Viewer(driverId, LiveRole.DRIVER, null);
        assertThat(asDriver.canSee(driver)).isTrue();
        assertThat(asDriver.canSee(me)).isTrue();
        assertThat(asDriver.canSee(other)).isTrue();

        Viewer asPassenger = new Viewer(passengerId, LiveRole.PASSENGER, bookingId);
        assertThat(asPassenger.canSee(driver)).isTrue();
        assertThat(asPassenger.canSee(me)).isTrue();
        assertThat(asPassenger.canSee(other)).isFalse();
    }

    @Test
    void subscribers_areTracked_removedOnEnd_andSessionForgotten() {
        Subscriber a = registry.subscribe(tripId, new SseEmitter(), new Viewer(driverId, LiveRole.DRIVER, null), now);
        Subscriber b = registry.subscribe(tripId, new SseEmitter(), new Viewer(passengerId, LiveRole.PASSENGER, bookingId), now);
        assertThat(registry.subscriberCount(tripId)).isEqualTo(2);
        assertThat(registry.tripsWithSubscribers()).containsExactly(tripId);

        registry.unsubscribe(tripId, a);
        assertThat(registry.subscribers(tripId)).containsExactly(b);

        assertThat(registry.remove(tripId)).containsExactly(b);
        assertThat(registry.hasSession(tripId)).isFalse();
        assertThat(registry.subscribers(tripId)).isEmpty();
        assertThat(registry.remove(tripId)).isEmpty();
    }

    @Test
    void evictIdle_forgetsOnlySessionsWithoutSubscribersAndWithoutRecentActivity() {
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        registry.subscribe(busy, new SseEmitter(), new Viewer(driverId, LiveRole.DRIVER, null), now);
        registry.participant(quiet, driverId, now);
        registry.participant(recent, driverId, now + LiveSessionRegistry.IDLE_SESSION_MILLIS);

        int evicted = registry.evictIdle(LiveSessionRegistry.IDLE_SESSION_MILLIS, now + LiveSessionRegistry.IDLE_SESSION_MILLIS + 1);

        assertThat(evicted).isEqualTo(1);
        assertThat(registry.hasSession(quiet)).isFalse();
        assertThat(registry.hasSession(busy)).isTrue();
        assertThat(registry.hasSession(recent)).isTrue();
        assertThat(registry.sessionCount()).isEqualTo(2);
    }

    private static LiveParticipant participant(LiveRole role, UUID bookingId, double lat) {
        return new LiveParticipant(role, bookingId, role == LiveRole.DRIVER ? "Rodrigue" : "Awa", lat, 2.35, null, null, null,
                Instant.parse("2026-09-10T08:00:00Z"), List.of());
    }
}
