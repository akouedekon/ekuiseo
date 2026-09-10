package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.RefundStatus;
import bj.ekuiseo.api.dto.booking.BookingPaymentStateResponse;
import bj.ekuiseo.api.dto.payment.RefundResponse;
import bj.ekuiseo.api.repository.DriverPayoutItemRepository;
import bj.ekuiseo.api.repository.LedgerEntryRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Etat de paiement consolide d une reservation (contrat A.2) : le dernier paiement de la
 * reservation, son remboursement vivant, le reglement en especes et les totaux du registre,
 * lus en base (jamais deduits du widget). Reserve au passager de la reservation ou au
 * conducteur du trajet.
 */
@Service
public class BookingPaymentStateService {

    private final BookingService bookingService;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final DriverPayoutItemRepository driverPayoutItemRepository;
    private final int pendingPaymentTtlMinutes;

    public BookingPaymentStateService(BookingService bookingService, PaymentRepository paymentRepository,
                                      RefundService refundService, LedgerEntryRepository ledgerEntryRepository,
                                      DriverPayoutItemRepository driverPayoutItemRepository,
                                      @org.springframework.beans.factory.annotation.Value("${ekuiseo.booking.pending-payment-ttl-minutes:20}") int pendingPaymentTtlMinutes) {
        this.bookingService = bookingService;
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.driverPayoutItemRepository = driverPayoutItemRepository;
        this.pendingPaymentTtlMinutes = pendingPaymentTtlMinutes;
    }

    @Transactional(readOnly = true)
    public BookingPaymentStateResponse get(UUID bookingId, UUID requesterId) {
        Booking booking = bookingService.findBooking(bookingId);
        bookingService.assertParticipant(booking, requesterId);
        return build(booking);
    }

    /** Forme partagee avec la fiche admin (AdminBookingService). */
    @Transactional(readOnly = true)
    public BookingPaymentStateResponse build(Booking booking) {
        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId());
        Optional<Payment> latest = payments.stream()
                .max(Comparator.comparing(Payment::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        Optional<RefundResponse> refund = refundService.latestForBooking(booking.getId());
        String state = state(booking, latest.orElse(null), refund.orElse(null));
        Instant expiresAt = booking.getStatus() == BookingStatus.PENDING_PAYMENT && booking.getPaymentMethod() != PaymentMethod.CASH
                ? (booking.getExpiresAt() != null ? booking.getExpiresAt()
                        : booking.getCreatedAt().plus(pendingPaymentTtlMinutes, ChronoUnit.MINUTES))
                : null;
        Instant paidAt = latest.filter(p -> RefundService.isRefundState(p.getStatus()) || p.getStatus() == PaymentStatus.SUCCEEDED)
                .map(p -> p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt()).orElse(null);
        LedgerEntryRepository.LedgerTotals totals = ledgerEntryRepository.getTotalsForBooking(booking.getId());
        long paidOut = driverPayoutItemRepository.findByBookingId(booking.getId())
                .filter(item -> item.getPayout().getStatus() == PayoutStatus.SETTLED)
                .map(item -> item.getNetAmount()).orElse(0L);
        BookingPaymentStateResponse.Ledger ledger = new BookingPaymentStateResponse.Ledger(
                totals == null ? 0 : totals.getPassengerPaid(),
                totals == null ? 0 : totals.getPlatformCommission() - totals.getCommissionReversed(),
                totals == null ? 0 : totals.getDriverShare() - totals.getDriverShareReversed(),
                totals == null ? 0 : totals.getRefunded(),
                paidOut, booking.getCashExpectedFcfa());
        return new BookingPaymentStateResponse(booking.getId(), booking.getStatus(), booking.getPaymentMethod(), state,
                latest.map(Payment::getId).orElse(null),
                latest.map(Payment::getProviderTxId).filter(id -> id != null && !id.startsWith("ekuiseo-")).orElse(null),
                booking.getPaymentMethod() == PaymentMethod.CASH ? 0 : booking.getDepositAmount(),
                latest.map(Payment::getVerifiedAmount).orElse(null), paidAt, expiresAt, refund.orElse(null),
                CashSettlementRules.toResponse(booking), ledger);
    }

    public static String state(Booking booking, Payment payment, RefundResponse refund) {
        if (refund != null) {
            RefundStatus status = refund.status();
            if (status == RefundStatus.SUCCEEDED) return BookingPaymentStateResponse.REFUNDED;
            if (status == RefundStatus.MANUAL_REVIEW) return BookingPaymentStateResponse.REFUND_MANUAL;
            if (status == RefundStatus.REQUESTED) return BookingPaymentStateResponse.REFUND_REQUESTED;
            if (status == RefundStatus.PROCESSING || status == RefundStatus.FAILED) return BookingPaymentStateResponse.REFUND_PROCESSING;
        }
        if (payment == null || booking.getPaymentMethod() == PaymentMethod.CASH) {
            return BookingPaymentStateResponse.NONE;
        }
        return switch (payment.getStatus()) {
            case INITIATED -> booking.getStatus() == BookingStatus.EXPIRED || booking.getStatus() == BookingStatus.CANCELLED_BY_PASSENGER
                    || booking.getStatus() == BookingStatus.CANCELLED_BY_DRIVER
                    ? BookingPaymentStateResponse.EXPIRED : BookingPaymentStateResponse.INITIATED;
            case SUCCEEDED -> BookingPaymentStateResponse.SUCCEEDED;
            case FAILED -> BookingPaymentStateResponse.FAILED;
            case REFUND_PENDING -> BookingPaymentStateResponse.REFUND_REQUESTED;
            case REFUND_MANUAL -> BookingPaymentStateResponse.REFUND_MANUAL;
            case REFUNDED -> BookingPaymentStateResponse.REFUNDED;
        };
    }
}
