package bj.ekuiseo.api.dto.booking;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.payment.RefundResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Etat de paiement consolide d une reservation (contrat A.2), GET /api/v1/bookings/{id}/payment-state :
 * une seule verite pour l ecran de confirmation, jamais derivee du widget.
 *
 * @param paymentState NONE (rien d initie, ou especes), INITIATED, SUCCEEDED, FAILED, EXPIRED,
 *                     REFUND_REQUESTED, REFUND_PROCESSING, REFUNDED, REFUND_MANUAL
 * @param amountDueFcfa acompte attendu (ou total en MOMO_FULL), 0 en CASH
 * @param expiresAt echeance de l acompte tant que la reservation est PENDING_PAYMENT
 */
public record BookingPaymentStateResponse(
        UUID bookingId,
        BookingStatus bookingStatus,
        PaymentMethod paymentMethod,
        String paymentState,
        UUID paymentId,
        String providerTxId,
        long amountDueFcfa,
        Long verifiedAmountFcfa,
        Instant paidAt,
        Instant expiresAt,
        RefundResponse refund,
        CashSettlementResponse cash,
        Ledger ledger
) {
    /** Totaux du registre pour cette reservation (contrat A.4), toujours renseignes (zeros sans ecriture). */
    public record Ledger(long passengerPaidFcfa, long platformCommissionFcfa, long driverShareFcfa,
                         long refundedFcfa, long paidOutFcfa, long cashExpectedFcfa) {
    }

    public static final String NONE = "NONE";
    public static final String INITIATED = "INITIATED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";
    public static final String REFUND_PROCESSING = "REFUND_PROCESSING";
    public static final String REFUNDED = "REFUNDED";
    public static final String REFUND_MANUAL = "REFUND_MANUAL";
}
