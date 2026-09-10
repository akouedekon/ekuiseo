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
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.dto.booking.CashSettlementResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contrat A.6 (V27) : confirmations croisees, reglement, litige, reglement tacite, regles pures. */
class CashSettlementServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CashSettlementService service = new CashSettlementService(bookingRepository, reportRepository,
            ledgerService, notificationService, auditService, 48);
    private User driver;
    private User passenger;
    private Booking booking;

    @BeforeEach
    void setUp() {
        driver = User.builder().id(UUID.randomUUID()).firstName("Awa").build();
        passenger = User.builder().id(UUID.randomUUID()).firstName("Jean").build();
        Trip trip = Trip.builder().id(UUID.randomUUID()).driver(driver).originLabel("Cotonou").destLabel("Bohicon")
                .departureAt(Instant.now().minus(2, ChronoUnit.HOURS)).build();
        booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(passenger).status(BookingStatus.CONFIRMED)
                .paymentMethod(PaymentMethod.MOMO_DEPOSIT).amount(7500).depositAmount(1000).balanceDueOnBoard(6500).build();
        CashSettlementRules.markExpected(booking);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void markExpected_andClear_followTheBalanceOnBoard() {
        assertThat(booking.getCashStatus()).isEqualTo(CashStatus.EXPECTED);
        assertThat(booking.getCashExpectedFcfa()).isEqualTo(6500);
        CashSettlementRules.clear(booking);
        assertThat(booking.getCashStatus()).isEqualTo(CashStatus.NOT_APPLICABLE);
        assertThat(CashSettlementRules.toResponse(booking)).isNull();

        Booking full = Booking.builder().balanceDueOnBoard(0).paymentMethod(PaymentMethod.MOMO_FULL).build();
        CashSettlementRules.markExpected(full);
        assertThat(full.getCashStatus()).isEqualTo(CashStatus.NOT_APPLICABLE);
    }

    @Test
    void driverConfirms_thenPassengerConfirms_settlesAndWritesTheLedger() {
        CashSettlementResponse first = service.driverConfirm(booking.getId(), driver.getId());

        assertThat(first.status()).isEqualTo(CashStatus.DRIVER_CONFIRMED);
        assertThat(first.driverConfirmedAt()).isNotNull();
        assertThat(first.expectedFcfa()).isEqualTo(6500);
        verify(notificationService).notify(eq(passenger), eq(NotificationType.CASH_CONFIRMATION_REQUESTED), any());
        verify(ledgerService, never()).recordCashSettled(any(), org.mockito.ArgumentMatchers.anyLong());

        CashSettlementResponse second = service.passengerConfirm(booking.getId(), passenger.getId());

        assertThat(second.status()).isEqualTo(CashStatus.SETTLED);
        assertThat(second.passengerConfirmedAt()).isNotNull();
        verify(ledgerService).recordCashSettled(booking, 6500);
        verify(auditService).log(eq(passenger.getId()), eq("CASH_PASSENGER_CONFIRMED"), eq("booking"), eq(booking.getId()), any());
        assertThatThrownBy(() -> service.driverConfirm(booking.getId(), driver.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void confirmations_areGuarded() {
        assertThatThrownBy(() -> service.driverConfirm(booking.getId(), passenger.getId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.passengerConfirm(booking.getId(), driver.getId())).isInstanceOf(ForbiddenException.class);

        booking.getTrip().setDepartureAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThatThrownBy(() -> service.driverConfirm(booking.getId(), driver.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("apres le depart");
        booking.getTrip().setDepartureAt(Instant.now().minus(1, ChronoUnit.HOURS));

        service.driverConfirm(booking.getId(), driver.getId());
        assertThatThrownBy(() -> service.driverConfirm(booking.getId(), driver.getId())).isInstanceOf(ConflictException.class);

        Booking noCash = Booking.builder().id(UUID.randomUUID()).trip(booking.getTrip()).passenger(passenger)
                .status(BookingStatus.CONFIRMED).paymentMethod(PaymentMethod.MOMO_FULL).balanceDueOnBoard(0).build();
        when(bookingRepository.findById(noCash.getId())).thenReturn(Optional.of(noCash));
        assertThatThrownBy(() -> service.passengerConfirm(noCash.getId(), passenger.getId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void dispute_opensAReport_andNotifiesBothParties() {
        CashSettlementResponse res = service.dispute(booking.getId(), passenger.getId(), "Le conducteur reclame 8 000 au lieu de 6 500");

        assertThat(res.status()).isEqualTo(CashStatus.DISPUTED);
        assertThat(res.disputedAt()).isNotNull();
        ArgumentCaptor<Report> report = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getReasonCode()).isEqualTo(ReportReason.CASH_DISPUTE.name());
        assertThat(report.getValue().getBookingId()).isEqualTo(booking.getId());
        assertThat(report.getValue().getReporter()).isEqualTo(passenger);
        assertThat(report.getValue().getReportedTrip()).isEqualTo(booking.getTrip());
        verify(notificationService).notifyCritical(eq(passenger), eq(NotificationType.CASH_DISPUTED), any());
        verify(notificationService).notifyCritical(eq(driver), eq(NotificationType.CASH_DISPUTED), any());
        assertThatThrownBy(() -> service.driverConfirm(booking.getId(), driver.getId())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.dispute(booking.getId(), driver.getId(), "encore")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.dispute(booking.getId(), UUID.randomUUID(), "x")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void tacitSettlement_needsOneConfirmation_noDispute_and48hAfterDeparture() {
        Instant now = Instant.now();
        assertThat(service.settleTacitly(booking.getId(), now)).isFalse(); // aucune confirmation

        service.driverConfirm(booking.getId(), driver.getId());
        assertThat(service.settleTacitly(booking.getId(), now)).isFalse(); // depart il y a 2 h seulement
        assertThat(service.settleTacitly(booking.getId(), now.plus(47, ChronoUnit.HOURS))).isTrue();
        assertThat(booking.getCashStatus()).isEqualTo(CashStatus.SETTLED);
        verify(ledgerService).recordCashSettled(booking, 6500);
        verify(auditService).log(eq(null), eq("CASH_SETTLED_TACITLY"), eq("booking"), eq(booking.getId()), any());
        assertThat(service.settleTacitly(booking.getId(), now.plus(50, ChronoUnit.HOURS))).isFalse(); // deja regle

        when(bookingRepository.findCashTacitSettlementsDue(any())).thenReturn(java.util.List.of(booking.getId()));
        assertThat(service.findTacitSettlementsDue(now)).containsExactly(booking.getId());
        assertThat(NotificationTemplates.render(NotificationType.CASH_CONFIRMATION_REQUESTED,
                Map.of("forDriver", true, "cashExpectedFcfa", 6500L)).subject()).contains("especes");
        assertThat(NotificationTemplates.render(NotificationType.CASH_DISPUTED, Map.of("disputedBy", "DRIVER")).body())
                .contains("le conducteur");
    }
}
