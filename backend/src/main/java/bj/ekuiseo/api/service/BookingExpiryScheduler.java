package bj.ekuiseo.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tache planifiee liberant les places des reservations PENDING_PAYMENT trop
 * anciennes (regle metier n.2). Executee chaque minute.
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
        int count = bookingService.expireStalePendingBookings();
        if (count > 0) {
            log.info("{} reservation(s) expiree(s) et places liberees", count);
        }
    }
}
