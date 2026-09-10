package bj.ekuiseo.api.dto.trip;

import bj.ekuiseo.api.domain.enums.TripStatus;

import java.time.Instant;
import java.util.List;

/**
 * Evenements du flux SSE {@code GET /api/v1/trips/{id}/live/stream} (contrat C, V28).
 * Le nom de l evenement SSE ({@code event:}) et le champ {@code type} du JSON sont
 * identiques, pour qu un client qui ne lit que les donnees s y retrouve.
 */
public final class LiveStreamEvent {

    private LiveStreamEvent() {
    }

    /** Raison de fermeture du flux. */
    public enum EndReason {
        TRIP_COMPLETED,
        SHARING_DISABLED,
        TRIP_CANCELLED
    }

    /** Premier evenement a l ouverture : tout ce que l appelant a le droit de voir. */
    public record Snapshot(String type, TripStatus tripStatus, boolean sharingEnabled, int intervalSeconds,
                           List<LiveParticipant> participants, Instant serverTime) {
        public static Snapshot of(TripStatus tripStatus, boolean sharingEnabled, int intervalSeconds,
                                  List<LiveParticipant> participants, Instant serverTime) {
            return new Snapshot("snapshot", tripStatus, sharingEnabled, intervalSeconds, participants, serverTime);
        }
    }

    /** Une position acceptee d un participant visible par l abonne. */
    public record Position(String type, LiveParticipant participant) {
        public static Position of(LiveParticipant participant) {
            return new Position("position", participant);
        }
    }

    /** Changement d etat du trajet ou du partage. */
    public record Status(String type, TripStatus tripStatus, boolean sharingEnabled, int intervalSeconds) {
        public static Status of(TripStatus tripStatus, boolean sharingEnabled, int intervalSeconds) {
            return new Status("status", tripStatus, sharingEnabled, intervalSeconds);
        }
    }

    /** Dernier evenement : le serveur ferme ensuite la connexion, le client ne doit pas se reconnecter. */
    public record End(String type, EndReason reason) {
        public static End of(EndReason reason) {
            return new End("end", reason);
        }
    }
}
