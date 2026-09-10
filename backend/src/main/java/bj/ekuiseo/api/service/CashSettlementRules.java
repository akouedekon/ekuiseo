package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.enums.CashStatus;
import bj.ekuiseo.api.dto.booking.CashSettlementResponse;

/**
 * Regles pures du solde en especes a bord (contrat A.6, V27), partagees par BookingService,
 * PaymentService et CashSettlementService sans dependance Spring : le solde est attendu des
 * qu une reservation est CONFIRMED avec un solde a bord, et n a plus lieu d etre quand la course
 * n aura pas lieu pour ce passager.
 */
public final class CashSettlementRules {

    private CashSettlementRules() {
    }

    /** A la confirmation : EXPECTED si un solde reste a regler a bord, sinon NOT_APPLICABLE. */
    public static void markExpected(Booking booking) {
        if (booking.getBalanceDueOnBoard() > 0) {
            if (booking.getCashStatus() == CashStatus.NOT_APPLICABLE) {
                booking.setCashStatus(CashStatus.EXPECTED);
            }
            booking.setCashExpectedFcfa(booking.getBalanceDueOnBoard());
        } else {
            booking.setCashStatus(CashStatus.NOT_APPLICABLE);
            booking.setCashExpectedFcfa(0L);
        }
    }

    /** Course qui n aura pas lieu pour ce passager (annulation, expiration, absence) : plus rien a regler a bord. */
    public static void clear(Booking booking) {
        if (booking.getCashStatus() == CashStatus.SETTLED || booking.getCashStatus() == CashStatus.DISPUTED) {
            return; // un reglement acte ou un litige ouvert ne s effacent pas
        }
        booking.setCashStatus(CashStatus.NOT_APPLICABLE);
        booking.setCashExpectedFcfa(0L);
        booking.setCashDriverConfirmedAt(null);
        booking.setCashPassengerConfirmedAt(null);
    }

    /** Vue du reglement ; null tant qu aucun solde a bord n est concerne. */
    public static CashSettlementResponse toResponse(Booking booking) {
        if (booking.getCashStatus() == null || booking.getCashStatus() == CashStatus.NOT_APPLICABLE) {
            return null;
        }
        return new CashSettlementResponse(booking.getCashStatus(), booking.getCashExpectedFcfa(),
                booking.getCashDriverConfirmedAt(), booking.getCashPassengerConfirmedAt(), booking.getCashDisputedAt());
    }
}
