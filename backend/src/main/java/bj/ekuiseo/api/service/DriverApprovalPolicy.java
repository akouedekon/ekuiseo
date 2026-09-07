package bj.ekuiseo.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Delai de reponse du conducteur a une demande de reservation (V19, point n.13 de l audit) :
 * {@code ekuiseo.booking.driver-approval-hours} (24 h par defaut), plafonne a 2 h avant le
 * depart pour que le passager sache a temps s il voyage. Une demande deposee dans les deux
 * dernieres heures laisse au conducteur jusqu au depart ; passe l heure de depart, une
 * demande sans reponse est de toute facon traitee comme un refus
 * ({@link BookingService#expireStaleApprovals}).
 */
@Component
public class DriverApprovalPolicy {

    /** Marge avant le depart au-dela de laquelle le conducteur ne peut plus faire attendre le passager. */
    static final Duration DEPARTURE_MARGIN = Duration.ofHours(2);

    private final int approvalHours;

    public DriverApprovalPolicy(@Value("${ekuiseo.booking.driver-approval-hours:24}") int approvalHours) {
        this.approvalHours = Math.max(1, approvalHours);
    }

    public int approvalHours() {
        return approvalHours;
    }

    /** Echeance de la reponse pour une demande deposee a {@code now} sur un trajet partant a {@code departureAt}. */
    public Instant deadline(Instant now, Instant departureAt) {
        Instant deadline = now.plus(Duration.ofHours(approvalHours));
        Instant cap = departureAt.minus(DEPARTURE_MARGIN);
        if (cap.isBefore(deadline)) {
            deadline = cap;
        }
        if (!deadline.isAfter(now)) {
            deadline = departureAt;
        }
        return deadline;
    }
}
