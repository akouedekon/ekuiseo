package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Refund;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.admin.AdminBookingDetailResponse;
import bj.ekuiseo.api.dto.admin.AdminBookingRowResponse;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.payment.AdminRefundResponse;
import bj.ekuiseo.api.dto.payment.RefundResponse;
import bj.ekuiseo.api.repository.AuditLogRepository;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.RefundRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.BookingPaymentStateService;
import bj.ekuiseo.api.service.CashSettlementRules;
import bj.ekuiseo.api.service.LedgerService;
import bj.ekuiseo.api.service.PaymentEventService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reservations vues du back-office (contrat A.10) : recherche paginee (debut d identifiant,
 * telephone ou nom du passager ou du conducteur, statut, periode) et fiche agregee (paiements,
 * remboursements, evenements, registre, audit). Etat de paiement calcule pour toute la page en
 * deux requetes (paiements et remboursements des reservations affichees), jamais par ligne.
 */
@Service
public class AdminBookingService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentEventService paymentEventService;
    private final LedgerService ledgerService;

    public AdminBookingService(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                               RefundRepository refundRepository, AuditLogRepository auditLogRepository,
                               PaymentEventService paymentEventService, LedgerService ledgerService) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.auditLogRepository = auditLogRepository;
        this.paymentEventService = paymentEventService;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public Page<AdminBookingRowResponse> search(String q, BookingStatus status, Instant from, Instant to, int page, int size) {
        Page<Booking> found = bookingRepository.findAll(specification(q, status, from, to),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(Paging.MAX_PAGE_SIZE, size)),
                        Sort.by("createdAt").descending()));
        List<UUID> ids = found.getContent().stream().map(Booking::getId).toList();
        Map<UUID, String> states = paymentStates(found.getContent(), ids);
        return found.map(b -> row(b, states.get(b.getId())));
    }

    @Transactional(readOnly = true)
    public AdminBookingDetailResponse detail(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Reservation introuvable"));
        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
        List<Refund> refunds = refundRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
        String state = paymentStates(List.of(booking), List.of(bookingId)).get(bookingId);
        Trip trip = booking.getTrip();
        User passenger = booking.getPassenger();
        User driver = trip.getDriver();
        AdminBookingDetailResponse.Booking summary = new AdminBookingDetailResponse.Booking(
                booking.getId(), booking.getStatus(), booking.getCreatedAt(), trip.getDepartureAt(),
                trip.getOriginLabel() + " -> " + trip.getDestLabel(), fullName(passenger), fullName(driver),
                booking.getSeats(), booking.getAmount(), booking.getDepositAmount(), booking.getPaymentMethod(),
                state, booking.getCashStatus().name(), booking.getServiceFee(), booking.getBalanceDueOnBoard(),
                booking.getPassengerConfirmation().name(),
                booking.getDriverNoShowResolution() == null ? null : booking.getDriverNoShowResolution().name(),
                CashSettlementRules.toResponse(booking), passenger.getId(), passenger.getPhone(), driver.getId(),
                trip.getId(), trip.getStatus().name());
        List<AdminPaymentResponse> paymentRows = payments.stream().map(AdminBookingService::toAdminPayment).toList();
        List<AdminRefundResponse> refundRows = refunds.stream().map(AdminRefundResponse::from).toList();
        List<AuditLog> logs = auditLogRepository.findAll(
                AuditService.toSpecification(new AuditService.Filter(null, null, "booking", bookingId, null, null)));
        List<AdminBookingDetailResponse.AuditEntry> audit = logs.stream()
                .sorted(Comparator.comparing(AuditLog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(l -> new AdminBookingDetailResponse.AuditEntry(l.getAction(), l.getActorId(), l.getCreatedAt(), l.getDetails()))
                .toList();
        return new AdminBookingDetailResponse(summary, paymentRows, refundRows,
                paymentEventService.listForPayments(payments.stream().map(Payment::getId).toList()),
                ledgerService.forBooking(bookingId), audit);
    }

    /** Etat de paiement consolide de chaque reservation, en deux requetes pour toute la liste. */
    private Map<UUID, String> paymentStates(List<Booking> bookings, List<UUID> ids) {
        Map<UUID, String> states = new HashMap<>();
        if (ids.isEmpty()) return states;
        Map<UUID, Payment> latestPayment = new HashMap<>();
        for (Payment p : paymentRepository.findByBookingIdIn(ids)) {
            UUID bookingId = p.getBooking().getId();
            Payment current = latestPayment.get(bookingId);
            if (current == null || (p.getCreatedAt() != null && current.getCreatedAt() != null
                    && p.getCreatedAt().isAfter(current.getCreatedAt()))) {
                latestPayment.put(bookingId, p);
            }
        }
        Map<UUID, Refund> latestRefund = new HashMap<>();
        for (Refund r : refundRepository.findByBookingIdInOrderByCreatedAtDesc(ids)) {
            if (r.getBookingId() == null) continue;
            if (r.getStatus() == RefundStatus.FAILED && r.getCompletedAt() != null) continue;
            latestRefund.putIfAbsent(r.getBookingId(), r);
        }
        for (Booking b : bookings) {
            Refund refund = latestRefund.get(b.getId());
            RefundResponse refundResponse = refund == null ? null : RefundResponse.from(refund);
            states.put(b.getId(), BookingPaymentStateService.state(b, latestPayment.get(b.getId()), refundResponse));
        }
        return states;
    }

    private static AdminBookingRowResponse row(Booking b, String paymentState) {
        Trip trip = b.getTrip();
        return new AdminBookingRowResponse(b.getId(), b.getStatus(), b.getCreatedAt(), trip.getDepartureAt(),
                trip.getOriginLabel() + " -> " + trip.getDestLabel(), fullName(b.getPassenger()), fullName(trip.getDriver()),
                b.getSeats(), b.getAmount(), b.getDepositAmount(), b.getPaymentMethod(), paymentState, b.getCashStatus().name());
    }

    private static String fullName(User user) {
        return user == null ? null : (Objects.toString(user.getFirstName(), "") + " " + Objects.toString(user.getLastName(), "")).trim();
    }

    static AdminPaymentResponse toAdminPayment(Payment p) {
        Booking b = p.getBooking();
        User passenger = b != null ? b.getPassenger() : null;
        return new AdminPaymentResponse(p.getId(), b != null ? b.getId() : null,
                p.getSubscription() != null ? p.getSubscription().getId() : null,
                passenger != null ? passenger.getId() : null, fullName(passenger),
                passenger != null ? passenger.getPhone() : null,
                p.getProviderTxId(), p.getAmount(), p.getStatus(), p.getRefundAmount(), p.getRefundReason(),
                p.getRefundRequestedAt(), p.getRefundAttempts(), p.getRefundLastError(), p.getRefundedAt(), p.getCreatedAt());
    }

    /**
     * Filtre : {@code q} sur le debut d identifiant (chaine), le telephone ou le nom du passager ou
     * du conducteur ; statut ; periode de creation ({@code to} exclusif). Les associations sont
     * chargees avec la page (fetch) et non dans la requete de comptage.
     */
    static Specification<Booking> specification(String q, BookingStatus status, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Join<Booking, Trip> trip;
            Join<Trip, User> driver;
            Join<Booking, User> passenger;
            if (query != null && !Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                @SuppressWarnings("unchecked")
                Join<Booking, Trip> tripFetch = (Join<Booking, Trip>) (Object) root.fetch("trip", JoinType.INNER);
                @SuppressWarnings("unchecked")
                Join<Trip, User> driverFetch = (Join<Trip, User>) (Object) tripFetch.fetch("driver", JoinType.INNER);
                @SuppressWarnings("unchecked")
                Join<Booking, User> passengerFetch = (Join<Booking, User>) (Object) root.fetch("passenger", JoinType.INNER);
                trip = tripFetch;
                driver = driverFetch;
                passenger = passengerFetch;
            } else {
                trip = root.join("trip");
                driver = trip.join("driver");
                passenger = root.join("passenger");
            }
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), to));
            String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty()) {
                String like = term + "%";
                String contains = "%" + term + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("id").as(String.class)), like),
                        cb.like(cb.lower(passenger.get("firstName")), contains),
                        cb.like(cb.lower(passenger.get("lastName")), contains),
                        cb.like(passenger.get("phone"), contains),
                        cb.like(cb.lower(driver.get("firstName")), contains),
                        cb.like(cb.lower(driver.get("lastName")), contains),
                        cb.like(driver.get("phone"), contains)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
