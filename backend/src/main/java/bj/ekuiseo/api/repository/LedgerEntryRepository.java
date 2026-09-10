package bj.ekuiseo.api.repository;

import bj.ekuiseo.api.domain.LedgerEntry;
import bj.ekuiseo.api.domain.enums.LedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Registre financier (V26) : ajout et lecture seulement ; jamais de mise a jour ni de suppression. */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {

    List<LedgerEntry> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<LedgerEntry> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    List<LedgerEntry> findByPaymentIdAndEntryType(UUID paymentId, LedgerEntryType entryType);

    boolean existsByPayoutIdAndEntryType(UUID payoutId, LedgerEntryType entryType);

    boolean existsByBookingIdAndEntryType(UUID bookingId, LedgerEntryType entryType);

    boolean existsByRefundIdAndEntryType(UUID refundId, LedgerEntryType entryType);

    /** Ecritures d une periode, plus anciennes d abord (export CSV). */
    List<LedgerEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);

    // ------------------------------------------------------------------
    // Agregations SQL natives (FinanceSummaryResponse, BookingPaymentStateResponse.ledger) :
    // jamais de chargement d ecritures en memoire pour un total. Alias en snake_case.
    // ------------------------------------------------------------------

    /** Totaux par type sur une periode, pour {@link #getTotals}. */
    interface LedgerTotals {
        long getPassengerPaid();

        long getProviderFees();

        long getPlatformCommission();

        long getDriverShare();

        long getRefunded();

        long getCommissionReversed();

        long getDriverShareReversed();

        long getPaidOut();

        long getCashOnBoard();
    }

    @Query(value = """
            select coalesce(sum(case when entry_type = 'PASSENGER_PAYMENT' then amount_fcfa end), 0) as passenger_paid,
                   coalesce(sum(case when entry_type = 'PROVIDER_FEE' then amount_fcfa end), 0) as provider_fees,
                   coalesce(sum(case when entry_type = 'PLATFORM_COMMISSION' then amount_fcfa end), 0) as platform_commission,
                   coalesce(sum(case when entry_type = 'DRIVER_SHARE' then amount_fcfa end), 0) as driver_share,
                   coalesce(sum(case when entry_type = 'REFUND' then amount_fcfa end), 0) as refunded,
                   coalesce(sum(case when entry_type = 'COMMISSION_REVERSAL' then amount_fcfa end), 0) as commission_reversed,
                   coalesce(sum(case when entry_type = 'DRIVER_SHARE_REVERSAL' then amount_fcfa end), 0) as driver_share_reversed,
                   coalesce(sum(case when entry_type = 'PAYOUT' then amount_fcfa end), 0) as paid_out,
                   coalesce(sum(case when entry_type = 'CASH_ON_BOARD' then amount_fcfa end), 0) as cash_on_board
            from ledger_entries
            where created_at >= :from and created_at < :to
            """, nativeQuery = true)
    LedgerTotals getTotals(@Param("from") Instant from, @Param("to") Instant to);

    /** Memes totaux, restreints a une reservation (etat de paiement consolide). */
    @Query(value = """
            select coalesce(sum(case when entry_type = 'PASSENGER_PAYMENT' then amount_fcfa end), 0) as passenger_paid,
                   coalesce(sum(case when entry_type = 'PROVIDER_FEE' then amount_fcfa end), 0) as provider_fees,
                   coalesce(sum(case when entry_type = 'PLATFORM_COMMISSION' then amount_fcfa end), 0) as platform_commission,
                   coalesce(sum(case when entry_type = 'DRIVER_SHARE' then amount_fcfa end), 0) as driver_share,
                   coalesce(sum(case when entry_type = 'REFUND' then amount_fcfa end), 0) as refunded,
                   coalesce(sum(case when entry_type = 'COMMISSION_REVERSAL' then amount_fcfa end), 0) as commission_reversed,
                   coalesce(sum(case when entry_type = 'DRIVER_SHARE_REVERSAL' then amount_fcfa end), 0) as driver_share_reversed,
                   coalesce(sum(case when entry_type = 'PAYOUT' then amount_fcfa end), 0) as paid_out,
                   coalesce(sum(case when entry_type = 'CASH_ON_BOARD' then amount_fcfa end), 0) as cash_on_board
            from ledger_entries
            where booking_id = :bookingId
            """, nativeQuery = true)
    LedgerTotals getTotalsForBooking(@Param("bookingId") UUID bookingId);

    /** Totaux par mois civil du Benin, pour {@link #getMonthlyTotals}. */
    interface MonthlyTotals {
        /** AAAA-MM. */
        String getMonth();

        long getPassengerPaid();

        long getCommission();

        long getRefunded();

        long getPaidOut();

        long getCashOnBoard();
    }

    @Query(value = """
            select to_char(created_at at time zone 'Africa/Porto-Novo', 'YYYY-MM') as month,
                   coalesce(sum(case when entry_type = 'PASSENGER_PAYMENT' then amount_fcfa end), 0) as passenger_paid,
                   coalesce(sum(case when entry_type = 'PLATFORM_COMMISSION' then amount_fcfa end), 0)
                     - coalesce(sum(case when entry_type = 'COMMISSION_REVERSAL' then amount_fcfa end), 0) as commission,
                   coalesce(sum(case when entry_type = 'REFUND' then amount_fcfa end), 0) as refunded,
                   coalesce(sum(case when entry_type = 'PAYOUT' then amount_fcfa end), 0) as paid_out,
                   coalesce(sum(case when entry_type = 'CASH_ON_BOARD' then amount_fcfa end), 0) as cash_on_board
            from ledger_entries
            where created_at >= :from and created_at < :to
            group by 1
            order by 1
            """, nativeQuery = true)
    List<MonthlyTotals> getMonthlyTotals(@Param("from") Instant from, @Param("to") Instant to);
}
