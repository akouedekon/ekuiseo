package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.report.AdminReportResponse;
import bj.ekuiseo.api.dto.report.CreateReportRequest;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.mapper.ReportMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F548 : trajet partage exige pour certains motifs, pas d auto-signalement, dedoublonnage, plafond ; F212 : issue notifiee. */
class ReportServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ReportService service = new ReportService(reportRepository, userRepository, tripRepository,
            bookingRepository, reportMapper, auditService, notificationService);

    private final User reporter = user();
    private final User driver = user();
    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).originLabel("Calavi").destLabel("Cotonou")
                .departureAt(Instant.now()).build();
        when(userRepository.findById(reporter.getId())).thenReturn(Optional.of(reporter));
        when(userRepository.findById(driver.getId())).thenReturn(Optional.of(driver));
        when(tripRepository.findById(trip.getId())).thenReturn(Optional.of(trip));
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(reportMapper.toResponse(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            return new ReportResponse(r.getId(), r.getReporter().getId(),
                    r.getReportedUser() != null ? r.getReportedUser().getId() : null,
                    r.getReportedTrip() != null ? r.getReportedTrip().getId() : null,
                    r.getReasonCode(), r.getDetails(), r.getStatus(), r.getResolutionNote(), Instant.now(), r.getResolvedAt());
        });
    }

    @Test
    void driver_cannotReportOwnTrip() {
        assertThatThrownBy(() -> service.create(driver.getId(), new CreateReportRequest(null, trip.getId(), "OTHER", null)))
                .isInstanceOf(BadRequestException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void noShow_withoutSharedBooking_isRejected_butFraudIsAccepted() {
        when(bookingRepository.findSharedBookings(any(), any(), anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, "NO_SHOW", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("reservation");

        ReportResponse fraud = service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, "FRAUD", "faux profil"));
        assertThat(fraud.reportedUserId()).isEqualTo(driver.getId());
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isNull();
    }

    @Test
    void sharedBooking_isRecordedOnReport_inBothDirections() {
        Booking shared = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(reporter).status(BookingStatus.COMPLETED).build();
        // Le conducteur signale son passager : le lien est cherche dans les deux sens.
        when(bookingRepository.findSharedBookings(driver.getId(), reporter.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW)))
                .thenReturn(List.of(shared));

        service.create(driver.getId(), new CreateReportRequest(reporter.getId(), null, "HARASSMENT", "insultes"));

        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(shared.getId());
    }

    @Test
    void tripReport_linksReporterBooking() {
        Booking mine = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(reporter).status(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(eq(trip.getId()), eq(reporter.getId()), anyList()))
                .thenReturn(List.of(mine));

        service.create(reporter.getId(), new CreateReportRequest(null, trip.getId(), "VEHICLE_MISMATCH", null));

        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(mine.getId());
        assertThat(saved.getValue().getReportedTrip()).isSameAs(trip);
    }

    @Test
    void openDuplicate_isConflict() {
        when(reportRepository.existsByReporterIdAndReportedUserIdAndStatusIn(eq(reporter.getId()), eq(driver.getId()),
                eq(List.of(ReportStatus.OPEN, ReportStatus.IN_REVIEW)))).thenReturn(true);

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, "FRAUD", null)))
                .isInstanceOf(ConflictException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void moreThanFivePerDay_isRateLimited() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(eq(reporter.getId()), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, "FRAUD", null)))
                .isInstanceOf(TooManyRequestsException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void resolve_notifiesReporter_butInReviewDoesNot() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedUser(driver).reasonCode("FRAUD").build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        service.updateStatus(UUID.randomUUID(), report.getId(), ReportStatus.IN_REVIEW);
        verify(notificationService, never()).notify(any(), any(), any());

        service.resolve(UUID.randomUUID(), report.getId(), new ResolveReportRequest(ReportStatus.DISMISSED, "Aucun manquement"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(reporter), eq(NotificationType.REPORT_RESOLVED), payload.capture());
        assertThat(payload.getValue()).containsEntry("status", "DISMISSED").containsEntry("resolutionNote", "Aucun manquement");
    }

    @Test
    void listForAdmin_isSortedNewestFirst_andExposesBookingId() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedTrip(trip)
                .bookingId(UUID.randomUUID()).reasonCode("NO_SHOW").build();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(reportRepository.findByStatus(eq(ReportStatus.OPEN), pageable.capture()))
                .thenAnswer(inv -> new PageImpl<>(List.of(report)));

        List<AdminReportResponse> rows = service.listForAdmin(ReportStatus.OPEN);

        assertThat(pageable.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bookingId()).isEqualTo(report.getBookingId());
        assertThat(rows.get(0).target().id()).isEqualTo(driver.getId());
    }

    private static User user() {
        return User.builder().id(UUID.randomUUID()).firstName("A").lastName("B").phone("+2290100000000").build();
    }
}
