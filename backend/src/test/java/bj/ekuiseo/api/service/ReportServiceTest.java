package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Conversation;
import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.report.AdminReportConversationResponse;
import bj.ekuiseo.api.dto.report.AdminReportResponse;
import bj.ekuiseo.api.dto.report.CreateReportRequest;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.mapper.ReportMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Constat F548 : trajet partage exige pour certains motifs, pas d auto-signalement, dedoublonnage,
 * plafond ; F212 : issue notifiee. Phase 2 (F549/F550/F552/F301/F307) : transitions strictes, note de
 * cloture, cible prevenue, recidive comptee, conversations journalisees.
 */
class ReportServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ReportMapper reportMapper = mock(ReportMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ReportService service = new ReportService(reportRepository, userRepository, tripRepository,
            bookingRepository, conversationRepository, messageRepository, reportMapper, auditService, notificationService);

    private final User reporter = user();
    private final User driver = user();
    private final UUID adminId = UUID.randomUUID();
    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(UUID.randomUUID()).driver(driver).originLabel("Calavi").destLabel("Cotonou")
                .departureAt(Instant.now()).build();
        when(userRepository.findById(reporter.getId())).thenReturn(Optional.of(reporter));
        when(userRepository.findById(driver.getId())).thenReturn(Optional.of(driver));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(User.builder().id(adminId).build()));
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
        assertThatThrownBy(() -> service.create(driver.getId(), new CreateReportRequest(null, trip.getId(), ReportReason.OTHER, null)))
                .isInstanceOf(BadRequestException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void noShow_withoutSharedBooking_isRejected_butFraudIsAccepted() {
        when(bookingRepository.findSharedBookings(any(), any(), anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, ReportReason.NO_SHOW, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("reservation");

        ReportResponse fraud = service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, ReportReason.FRAUD, "faux profil"));
        assertThat(fraud.reportedUserId()).isEqualTo(driver.getId());
        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isNull();
    }

    @Test
    void create_notifiesTheTarget_withoutRevealingTheReporter() {
        service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, bj.ekuiseo.api.domain.enums.ReportReason.FRAUD, "details prives"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(driver), eq(NotificationType.REPORT_RECEIVED), payload.capture());
        assertThat(payload.getValue()).containsEntry("reason", "FRAUD");
        assertThat(payload.getValue().values()).noneMatch(v -> String.valueOf(v).contains(reporter.getId().toString()));
        assertThat(payload.getValue()).doesNotContainKey("details").doesNotContainKey("reporterId");
    }

    @Test
    void tripReport_notifiesTheDriver() {
        service.create(reporter.getId(), new CreateReportRequest(null, trip.getId(), bj.ekuiseo.api.domain.enums.ReportReason.OTHER, null));

        verify(notificationService).notify(eq(driver), eq(NotificationType.REPORT_RECEIVED), any());
    }

    @Test
    void sharedBooking_isRecordedOnReport_inBothDirections() {
        Booking shared = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(reporter).status(BookingStatus.COMPLETED).build();
        // Le conducteur signale son passager : le lien est cherche dans les deux sens.
        when(bookingRepository.findSharedBookings(driver.getId(), reporter.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW)))
                .thenReturn(List.of(shared));

        service.create(driver.getId(), new CreateReportRequest(reporter.getId(), null, ReportReason.HARASSMENT, "insultes"));

        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(shared.getId());
    }

    @Test
    void tripReport_linksReporterBooking() {
        Booking mine = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(reporter).status(BookingStatus.CONFIRMED).build();
        when(bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(eq(trip.getId()), eq(reporter.getId()), anyList()))
                .thenReturn(List.of(mine));

        service.create(reporter.getId(), new CreateReportRequest(null, trip.getId(), ReportReason.VEHICLE_MISMATCH, null));

        ArgumentCaptor<Report> saved = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingId()).isEqualTo(mine.getId());
        assertThat(saved.getValue().getReportedTrip()).isSameAs(trip);
    }

    @Test
    void openDuplicate_isConflict() {
        when(reportRepository.existsByReporterIdAndReportedUserIdAndStatusIn(eq(reporter.getId()), eq(driver.getId()),
                eq(List.of(ReportStatus.OPEN, ReportStatus.IN_REVIEW)))).thenReturn(true);

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, ReportReason.FRAUD, null)))
                .isInstanceOf(ConflictException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void moreThanFivePerDay_isRateLimited() {
        when(reportRepository.countByReporterIdAndCreatedAtAfter(eq(reporter.getId()), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.create(reporter.getId(), new CreateReportRequest(driver.getId(), null, ReportReason.FRAUD, null)))
                .isInstanceOf(TooManyRequestsException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void resolve_notifiesReporter_butInReviewDoesNot() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedUser(driver).reasonCode("FRAUD").build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        AdminReportResponse inReview = service.updateStatus(adminId, report.getId(), ReportStatus.IN_REVIEW);
        verify(notificationService, never()).notify(any(), any(), any());
        assertThat(inReview.resolvedAt()).isNull();
        assertThat(inReview.resolvedBy()).isNull();

        service.resolve(adminId, report.getId(), new ResolveReportRequest(ReportStatus.DISMISSED, "Aucun manquement"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(reporter), eq(NotificationType.REPORT_RESOLVED), payload.capture());
        assertThat(payload.getValue()).containsEntry("status", "DISMISSED").containsEntry("resolutionNote", "Aucun manquement");
        assertThat(report.getResolvedAt()).isNotNull();
        assertThat(report.getResolvedBy().getId()).isEqualTo(adminId);
    }

    @Test
    void transitions_areStrict() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedUser(driver).reasonCode("FRAUD").build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        // OPEN -> RESOLVED saute l examen : refuse.
        assertThatThrownBy(() -> service.updateStatus(adminId, report.getId(), ReportStatus.RESOLVED, "note"))
                .isInstanceOf(ConflictException.class);
        // OPEN -> OPEN : refuse.
        assertThatThrownBy(() -> service.updateStatus(adminId, report.getId(), ReportStatus.OPEN))
                .isInstanceOf(ConflictException.class);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);

        service.updateStatus(adminId, report.getId(), ReportStatus.IN_REVIEW);
        // IN_REVIEW -> OPEN : pas de retour en arriere.
        assertThatThrownBy(() -> service.updateStatus(adminId, report.getId(), ReportStatus.OPEN))
                .isInstanceOf(ConflictException.class);

        service.updateStatus(adminId, report.getId(), ReportStatus.RESOLVED, "Compte suspendu");
        // Un signalement clos ne bouge plus.
        assertThatThrownBy(() -> service.updateStatus(adminId, report.getId(), ReportStatus.DISMISSED, "x"))
                .isInstanceOf(ConflictException.class);
        assertThat(ReportService.isAllowedTransition(ReportStatus.DISMISSED, ReportStatus.IN_REVIEW)).isFalse();
    }

    @Test
    void closing_requiresANote() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedUser(driver).reasonCode("FRAUD")
                .status(ReportStatus.IN_REVIEW).build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.updateStatus(adminId, report.getId(), ReportStatus.RESOLVED, "  "))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("note");
        assertThat(report.getResolvedAt()).isNull();
        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    void listForAdmin_isSortedNewestFirst_andExposesBookingIdAndPriorReports() {
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedTrip(trip)
                .bookingId(UUID.randomUUID()).reasonCode("NO_SHOW").build();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(reportRepository.findByStatusWithParties(eq(ReportStatus.OPEN), pageable.capture()))
                .thenAnswer(inv -> new PageImpl<>(List.of(report)));
        when(reportRepository.countOthersAgainstTarget(report.getId(), driver.getId())).thenReturn(3L);

        List<AdminReportResponse> rows = service.listForAdmin(ReportStatus.OPEN, 2, 500).getContent();

        assertThat(pageable.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        // Constat F237 : pagination serveur, taille bornee a 100 quoi que demande le client.
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bookingId()).isEqualTo(report.getBookingId());
        assertThat(rows.get(0).target().id()).isEqualTo(driver.getId());
        assertThat(rows.get(0).priorReportsAgainstTarget()).isEqualTo(3L);
    }

    @Test
    void conversations_returnLinkedExchanges_andAuditEachRead() {
        Booking booking = Booking.builder().id(UUID.randomUUID()).trip(trip).passenger(reporter).status(BookingStatus.COMPLETED).build();
        Report report = Report.builder().id(UUID.randomUUID()).reporter(reporter).reportedUser(driver)
                .bookingId(booking.getId()).reasonCode("HARASSMENT").build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));
        when(bookingRepository.findSharedBookings(eq(reporter.getId()), eq(driver.getId()), anyList())).thenReturn(List.of(booking));
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).booking(booking).build();
        when(conversationRepository.findByBookingIdIn(anyCollection())).thenReturn(List.of(conversation));
        Message message = Message.builder().id(UUID.randomUUID()).conversation(conversation).sender(driver)
                .body("Bonjour").createdAt(Instant.now()).build();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())).thenReturn(List.of(message));

        List<AdminReportConversationResponse> rows = service.conversations(adminId, report.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).bookingId()).isEqualTo(booking.getId());
        assertThat(rows.get(0).participants()).extracting(AdminReportConversationResponse.Participant::id)
                .containsExactly(reporter.getId(), driver.getId());
        assertThat(rows.get(0).messages()).hasSize(1);
        assertThat(rows.get(0).messages().get(0).senderId()).isEqualTo(driver.getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(adminId), eq("ADMIN_CONVERSATION_VIEWED"), eq("conversation"), eq(conversation.getId()), details.capture());
        assertThat(details.getValue()).containsEntry("reportId", report.getId().toString());
    }

    private static User user() {
        return User.builder().id(UUID.randomUUID()).firstName("A").lastName("B").phone("+2290100000000").build();
    }
}
