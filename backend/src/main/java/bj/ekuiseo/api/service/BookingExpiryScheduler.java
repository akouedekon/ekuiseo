package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Taches planifiees, executees chaque minute, qui liberent les places bloquees :
 * <ul>
 *   <li>reservations PENDING_PAYMENT trop anciennes (regle metier n.2) ;</li>
 *   <li>demandes PENDING_DRIVER_APPROVAL restees sans reponse du conducteur dans le delai,
 *       ou dont le trajet est parti (V19) : traitees comme un refus, acompte rembourse.</li>
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
}
