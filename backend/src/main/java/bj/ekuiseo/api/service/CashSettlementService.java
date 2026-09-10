package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.CashStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.dto.booking.CashSettlementResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Solde en especes regle a bord comme vrai moyen de paiement (contrat A.6, V27). Une
 * reservation confirmee avec un solde a bord (modes CASH et MOMO_DEPOSIT) est EXPECTED ; apres
 * le depart, le conducteur et le passager confirment chacun le reglement :
 * <ul>
 *   <li>les deux confirment -> SETTLED, ecriture CASH_ON_BOARD au registre ;</li>
 *   <li>une seule confirmation sans litige {@code ekuiseo.cash.tacit-settlement-hours} (48 h)
 *       apres le depart -> SETTLED tacite ({@link #settleTacitly}, CashSettlementScheduler) ;</li>
 *   <li>l un ou l autre conteste -> DISPUTED, signalement CASH_DISPUTE pour la moderation, les
 *       deux parties prevenues.</li>
 * </ul>
 * Aucun montant ne vient du client : le montant attendu est celui fige a la confirmation.
 */
@Service
public class CashSettlementService {

    private static final Logger log = LoggerFactory.getLogger(CashSettlementService.class);
    private static final List<BookingStatus> SETTLEABLE = List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

    private final BookingRepository bookingRepository;
    private final ReportRepository reportRepository;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Duration tacitSettlement;

    public CashSettlementService(BookingRepository bookingRepository, ReportRepository reportRepository,
                                 LedgerService ledgerService, NotificationService notificationService,
                                 AuditService auditService,
                                 @Value("${ekuiseo.cash.tacit-settlement-hours:48}") long tacitSettlementHours) {
        this.bookingRepository = bookingRepository;
        this.reportRepository = reportRepository;
        this.ledgerService = ledgerService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.tacitSettlement = Duration.ofHours(tacitSettlementHours);
    }

    /** POST /bookings/{id}/cash/driver-confirm : le conducteur a recu le solde. */
    @Transactional
    public CashSettlementResponse driverConfirm(UUID bookingId, UUID driverId) {
        Booking booking = load(bookingId);
        if (!booking.getTrip().getDriver().getId().equals(driverId)) {
            throw new ForbiddenException("Vous n etes pas le conducteur de ce trajet");
        }
        requireConfirmable(booking);
        if (booking.getCashDriverConfirmedAt() != null) {
            throw new ConflictException("Vous avez deja confirme ce reglement");
        }
        Instant now = Instant.now();
        booking.setCashDriverConfirmedAt(now);
        if (booking.getCashPassengerConfirmedAt() != null) {
            settle(booking, "les deux parties ont confirme");
        } else {
            booking.setCashStatus(CashStatus.DRIVER_CONFIRMED);
            bookingRepository.save(booking);
            notifyOtherParty(booking, booking.getPassenger(), false);
        }
        auditService.log(driverId, "CASH_DRIVER_CONFIRMED", "booking", booking.getId(),
                Map.of("cashExpectedFcfa", booking.getCashExpectedFcfa(), "cashStatus", booking.getCashStatus().name()));
        return CashSettlementRules.toResponse(booking);
    }

    /** POST /bookings/{id}/cash/passenger-confirm : le passager a regle le solde. */
    @Transactional
    public CashSettlementResponse passengerConfirm(UUID bookingId, UUID passengerId) {
        Booking booking = load(bookingId);
        if (!booking.getPassenger().getId().equals(passengerId)) {
            throw new ForbiddenException("Cette reservation n est pas la votre");
        }
        requireConfirmable(booking);
        if (booking.getCashPassengerConfirmedAt() != null) {
            throw new ConflictException("Vous avez deja confirme ce reglement");
        }
        Instant now = Instant.now();
        booking.setCashPassengerConfirmedAt(now);
        if (booking.getCashDriverConfirmedAt() != null) {
            settle(booking, "les deux parties ont confirme");
        } else {
            booking.setCashStatus(CashStatus.PASSENGER_CONFIRMED);
            bookingRepository.save(booking);
            notifyOtherParty(booking, booking.getTrip().getDriver(), true);
        }
        auditService.log(passengerId, "CASH_PASSENGER_CONFIRMED", "booking", booking.getId(),
                Map.of("cashExpectedFcfa", booking.getCashExpectedFcfa(), "cashStatus", booking.getCashStatus().name()));
        return CashSettlementRules.toResponse(booking);
    }

    /** POST /bookings/{id}/cash/dispute : desaccord de l une des parties, signalement CASH_DISPUTE. */
    @Transactional
    public CashSettlementResponse dispute(UUID bookingId, UUID userId, String details) {
        Booking booking = load(bookingId);
        Trip trip = booking.getTrip();
        boolean isDriver = trip.getDriver().getId().equals(userId);
        boolean isPassenger = booking.getPassenger().getId().equals(userId);
        if (!isDriver && !isPassenger) {
            throw new ForbiddenException("Vous n etes pas partie a cette reservation");
        }
        if (details == null || details.isBlank()) {
            throw new BadRequestException("Expliquez le desaccord");
        }
        requireDisputable(booking);
        Instant now = Instant.now();
        booking.setCashStatus(CashStatus.DISPUTED);
        booking.setCashDisputedAt(now);
        booking.setCashDisputeDetails(details.trim());
        bookingRepository.save(booking);
        User reporter = isDriver ? trip.getDriver() : booking.getPassenger();
        Report report = reportRepository.save(Report.builder()
                .reporter(reporter)
                .reportedUser(isDriver ? booking.getPassenger() : null)
                .reportedTrip(isDriver ? null : trip)
                .bookingId(booking.getId())
                .reasonCode(ReportReason.CASH_DISPUTE.name())
                .details("Desaccord sur le solde en especes (" + booking.getCashExpectedFcfa() + " FCFA attendus) : " + details.trim())
                .build());
        auditService.log(userId, "CASH_DISPUTED", "booking", booking.getId(),
                Map.of("reportId", report.getId().toString(), "cashExpectedFcfa", booking.getCashExpectedFcfa(),
                        "disputedBy", isDriver ? "DRIVER" : "PASSENGER"));
        Map<String, Object> payload = NotificationTemplates.payload("bookingId", booking.getId().toString(),
                "tripId", trip.getId().toString(), "reportId", report.getId().toString(),
                "cashExpectedFcfa", booking.getCashExpectedFcfa(), "disputedBy", isDriver ? "DRIVER" : "PASSENGER",
                "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                "departureAt", Objects.toString(trip.getDepartureAt(), ""));
        notificationService.notifyCritical(booking.getPassenger(), NotificationType.CASH_DISPUTED, payload);
        notificationService.notifyCritical(trip.getDriver(), NotificationType.CASH_DISPUTED, payload);
        log.info("Reservation {} : litige especes ouvert par {} (signalement {})", booking.getId(),
                isDriver ? "le conducteur" : "le passager", report.getId());
        return CashSettlementRules.toResponse(booking);
    }

    /**
     * Reglement tacite (scheduler) : une seule confirmation, aucun litige, trajet parti depuis
     * {@code tacitSettlement}. Une transaction par reservation ; renvoie vrai si reglee.
     */
    @Transactional
    public boolean settleTacitly(UUID bookingId, Instant now) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) return false;
        boolean oneSided = booking.getCashStatus() == CashStatus.DRIVER_CONFIRMED
                || booking.getCashStatus() == CashStatus.PASSENGER_CONFIRMED;
        if (!oneSided || !SETTLEABLE.contains(booking.getStatus())
                || booking.getTrip().getDepartureAt().plus(tacitSettlement).isAfter(now)) {
            return false;
        }
        settle(booking, "confirmation tacite " + tacitSettlement.toHours() + " h apres le depart");
        auditService.log(null, "CASH_SETTLED_TACITLY", "booking", booking.getId(),
                Map.of("cashExpectedFcfa", booking.getCashExpectedFcfa()));
        return true;
    }

    /** Identifiants des reservations candidates au reglement tacite. */
    @Transactional(readOnly = true)
    public List<UUID> findTacitSettlementsDue(Instant now) {
        return bookingRepository.findCashTacitSettlementsDue(now.minus(tacitSettlement));
    }

    private void settle(Booking booking, String how) {
        booking.setCashStatus(CashStatus.SETTLED);
        bookingRepository.save(booking);
        ledgerService.recordCashSettled(booking, booking.getCashExpectedFcfa());
        log.info("Reservation {} : solde en especes regle ({} FCFA, {})", booking.getId(), booking.getCashExpectedFcfa(), how);
    }

    private void notifyOtherParty(Booking booking, User recipient, boolean forDriver) {
        Trip trip = booking.getTrip();
        notificationService.notify(recipient, NotificationType.CASH_CONFIRMATION_REQUESTED,
                NotificationTemplates.payload("bookingId", booking.getId().toString(), "tripId", trip.getId().toString(),
                        "cashExpectedFcfa", booking.getCashExpectedFcfa(), "forDriver", forDriver,
                        "route", trip.getOriginLabel() + " -> " + trip.getDestLabel(),
                        "departureAt", Objects.toString(trip.getDepartureAt(), "")));
    }

    private Booking load(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new bj.ekuiseo.api.common.exception.NotFoundException("Reservation introuvable"));
    }

    private static void requireConfirmable(Booking booking) {
        requireDeparted(booking);
        switch (booking.getCashStatus()) {
            case NOT_APPLICABLE -> throw new BadRequestException("Aucun solde en especes n est attendu sur cette reservation");
            case SETTLED -> throw new ConflictException("Le reglement en especes est deja acte");
            case DISPUTED -> throw new ConflictException("Un desaccord est en cours d examen par la moderation");
            default -> {
                // EXPECTED, DRIVER_CONFIRMED, PASSENGER_CONFIRMED : confirmable
            }
        }
    }

    private static void requireDisputable(Booking booking) {
        requireDeparted(booking);
        switch (booking.getCashStatus()) {
            case NOT_APPLICABLE -> throw new BadRequestException("Aucun solde en especes n est attendu sur cette reservation");
            case DISPUTED -> throw new ConflictException("Un desaccord est deja en cours d examen");
            default -> {
                // EXPECTED, une confirmation, ou SETTLED (un reglement acte peut encore etre conteste) : ouvert
            }
        }
    }

    private static void requireDeparted(Booking booking) {
        if (!SETTLEABLE.contains(booking.getStatus())) {
            throw new BadRequestException("Seule une reservation confirmee ou terminee a un solde a regler a bord");
        }
        if (Instant.now().isBefore(booking.getTrip().getDepartureAt())) {
            throw new BadRequestException("Le reglement en especes se confirme apres le depart");
        }
    }
}
