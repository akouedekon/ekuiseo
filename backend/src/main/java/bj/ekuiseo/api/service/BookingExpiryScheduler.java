package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tache planifiee liberant les places des reservations PENDING_PAYMENT trop
 * anciennes (regle metier n.2). Executee chaque minute. Toute exception est
 * journalisee et absorbee (constat F128) : l execution suivante repart normalement.
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
}
