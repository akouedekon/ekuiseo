package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.enums.NoShowResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Taches planifiees, executees chaque minute, qui liberent les places bloquees :
 * <ul>
 *   <li>reservations PENDING_PAYMENT trop anciennes (regle metier n.2) ;</li>
 *   <li>demandes PENDING_DRIVER_APPROVAL restees sans reponse du conducteur dans le delai,
 *       ou dont le trajet est parti (V19) : traitees comme un refus, acompte rembourse ;</li>
 *   <li>conducteur declare absent et non conteste dans la fenetre (V25) : acompte rembourse.</li>
 * </ul>
 * Toute exception est journalisee et absorbee (constat F128) : l execution suivante repart
 * normalement, et les deux balayages sont independants.
 */
@Component
public class BookingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryScheduler.class);

    private final BookingService bookingService;

    public BookingExpiryScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedRate = 60_000)
    public void expireStaleBookings() {
        try {
            int count = bookingService.expireStalePendingBookings();
            if (count > 0) {
                log.info("{} reservation(s) expiree(s) et places liberees", count);
            }
        } catch (RuntimeException ex) {
            log.error("Expiration des reservations impayees : echec de l execution", ex);
        }
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void expireStaleApprovals() {
        try {
            int count = bookingService.expireStaleApprovals();
            if (count > 0) {
                log.info("{} demande(s) sans reponse du conducteur traitee(s) comme un refus", count);
            }
        } catch (RuntimeException ex) {
            log.error("Expiration des demandes en attente du conducteur : echec de l execution", ex);
        }
    }

    /**
     * Toutes les 5 minutes (V25) : conducteur declare absent, fenetre de contestation echue sans
     * contestation ni decision -> acompte rembourse au passager. Une transaction par reservation.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void refundUncontestedDriverNoShows() {
        List<UUID> due;
        try {
            due = bookingService.findDriverNoShowRefundsDue(Instant.now());
        } catch (RuntimeException ex) {
            log.error("Remboursements conducteur absent : lecture des dossiers echus en echec", ex);
            return;
        }
        int done = 0;
        for (UUID bookingId : due) {
            try {
                bookingService.resolveDriverNoShow(null, bookingId, NoShowResolution.REFUND_PASSENGER, null);
                done++;
            } catch (RuntimeException ex) {
                log.error("Remboursement automatique de la reservation {} (conducteur absent) en echec", bookingId, ex);
            }
        }
        if (done > 0) {
            log.info("{} acompte(s) rembourse(s) automatiquement : conducteur absent non conteste", done);
        }
    }
}
