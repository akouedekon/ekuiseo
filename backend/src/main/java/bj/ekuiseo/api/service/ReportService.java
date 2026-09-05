package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.ReportReason;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Signalements d'utilisateurs ou de trajets (regle metier n.15), et leur moderation cote admin.
 *
 * <p>Lot 1.4 (constat F548) : un conducteur ne signale pas son propre trajet ; les motifs
 * qui supposent un trajet partage (absence, conduite dangereuse, harcelement, vehicule
 * different) exigent une reservation entre les deux parties, enregistree dans
 * {@code reports.booking_id} ; un signalement encore ouvert vers la meme cible n est pas
 * doublonne (409) ; au plus 5 signalements par auteur et par 24 h (429). L auteur est
 * prevenu de l issue (REPORT_RESOLVED, constat F212).</p>
 */
@Service
public class ReportService {

    /** Plafond de la liste admin (a plat, non paginee, voir GET /api/v1/admin/reports). */
    private static final int ADMIN_LIST_LIMIT = 200;
    /** Plafond de signalements par auteur sur {@link #RATE_WINDOW}. */
    static final int MAX_REPORTS_PER_WINDOW = 5;
    static final Duration RATE_WINDOW = Duration.ofHours(24);

    /** Motifs qui n ont de sens qu entre deux personnes ayant partage un trajet. */
    private static final Set<ReportReason> REASONS_REQUIRING_BOOKING = EnumSet.of(
            ReportReason.NO_SHOW, ReportReason.DANGEROUS_DRIVING, ReportReason.HARASSMENT, ReportReason.VEHICLE_MISMATCH);
    /** Reservations qui prouvent une interaction : confirmees, voyagees, ou absence constatee. */
    private static final List<BookingStatus> LINKING_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    private static final List<ReportStatus> OPEN_STATUSES = List.of(ReportStatus.OPEN, ReportStatus.IN_REVIEW);

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final ReportMapper reportMapper;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public ReportService(ReportRepository reportRepository, UserRepository userRepository,
                          TripRepository tripRepository, BookingRepository bookingRepository,
                          ReportMapper reportMapper, AuditService auditService,
                          NotificationService notificationService) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.reportMapper = reportMapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest req) {
        User reporter = userRepository.findById(reporterId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        ReportReason reason = parseReason(req.reasonCode());
        Report.ReportBuilder builder = Report.builder().reporter(reporter).reasonCode(req.reasonCode()).details(req.details());

        Booking link;
        if (req.reportedUserId() != null) {
            if (req.reportedUserId().equals(reporterId)) {
                throw new BadRequestException("Vous ne pouvez pas vous signaler vous-meme");
            }
            User target = userRepository.findById(req.reportedUserId())
                    .orElseThrow(() -> new NotFoundException("Utilisateur signale introuvable"));
            if (reportRepository.existsByReporterIdAndReportedUserIdAndStatusIn(reporterId, target.getId(), OPEN_STATUSES)) {
                throw new ConflictException("Vous avez deja signale cette personne : la moderation traite votre signalement");
            }
            link = bookingRepository.findSharedBookings(reporterId, target.getId(), LINKING_STATUSES).stream()
                    .findFirst().orElse(null);
            builder.reportedUser(target);
        } else if (req.reportedTripId() != null) {
            Trip trip = tripRepository.findById(req.reportedTripId())
                    .orElseThrow(() -> new NotFoundException("Trajet signale introuvable"));
            if (trip.getDriver().getId().equals(reporterId)) {
                throw new BadRequestException("Vous ne pouvez pas signaler votre propre trajet");
            }
            if (reportRepository.existsByReporterIdAndReportedTripIdAndStatusIn(reporterId, trip.getId(), OPEN_STATUSES)) {
                throw new ConflictException("Vous avez deja signale ce trajet : la moderation traite votre signalement");
            }
            link = bookingRepository.findByTripIdAndPassengerIdAndStatusInOrderByCreatedAtDesc(trip.getId(), reporterId, LINKING_STATUSES)
                    .stream().findFirst().orElse(null);
            builder.reportedTrip(trip);
        } else {
            throw new BadRequestException("Une cible (utilisateur ou trajet) doit etre precisee");
        }

        if (link == null && REASONS_REQUIRING_BOOKING.contains(reason)) {
            throw new BadRequestException("Ce motif suppose un trajet partage : aucune reservation ne vous lie a cette cible");
        }
        long recent = reportRepository.countByReporterIdAndCreatedAtAfter(reporterId, Instant.now().minus(RATE_WINDOW));
        if (recent >= MAX_REPORTS_PER_WINDOW) {
            throw new TooManyRequestsException("Vous avez atteint le nombre maximal de signalements sur 24 heures");
        }
        if (link != null) {
            builder.bookingId(link.getId());
        }

        Report report = reportRepository.save(builder.build());
        auditService.log(reporterId, "REPORT_CREATED", "report", report.getId(),
                Map.of("reasonCode", req.reasonCode(), "bookingId", link != null ? link.getId().toString() : ""));
        return reportMapper.toResponse(report);
    }

    @Transactional(readOnly = true)
    public Page<ReportResponse> list(ReportStatus status, Pageable pageable) {
        Page<Report> page = status != null ? reportRepository.findByStatus(status, pageable) : reportRepository.findAll(pageable);
        return page.map(reportMapper::toResponse);
    }

    @Transactional
    public ReportResponse resolve(UUID adminId, UUID reportId, ResolveReportRequest req) {
        Report report = reportRepository.findById(reportId).orElseThrow(() -> new NotFoundException("Signalement introuvable"));
        report.setStatus(req.status());
        report.setResolutionNote(req.resolutionNote());
        User admin = userRepository.findById(adminId).orElse(null);
        report.setResolvedBy(admin);
        report.setResolvedAt(Instant.now());
        report = reportRepository.save(report);
        auditService.log(adminId, "REPORT_RESOLVED", "report", report.getId(),
                Map.of("status", req.status().name()));
        notifyReporterIfClosed(report);
        return reportMapper.toResponse(report);
    }

    /**
     * Vue back-office a plat, GET /api/v1/admin/reports?status=... (le front
     * attend un tableau simple, pas une Page), du plus recent au plus ancien.
     * Plafonnee a ADMIN_LIST_LIMIT plutot que veritablement paginee : la file de
     * moderation est censee rester courte (les signalements traites en sortent au
     * fil de l'eau).
     */
    @Transactional(readOnly = true)
    public List<AdminReportResponse> listForAdmin(ReportStatus status) {
        Pageable pageable = PageRequest.of(0, ADMIN_LIST_LIMIT, Sort.by("createdAt").descending());
        Page<Report> page = status != null ? reportRepository.findByStatus(status, pageable) : reportRepository.findAll(pageable);
        return page.getContent().stream().map(this::toAdminResponse).toList();
    }

    /** PATCH /api/v1/admin/reports/{id} : variante ciblee de {@link #resolve} (sans note de resolution). */
    @Transactional
    public AdminReportResponse updateStatus(UUID adminId, UUID reportId, ReportStatus status) {
        Report report = reportRepository.findById(reportId).orElseThrow(() -> new NotFoundException("Signalement introuvable"));
        report.setStatus(status);
        report.setResolvedBy(userRepository.findById(adminId).orElse(null));
        report.setResolvedAt(Instant.now());
        report = reportRepository.save(report);
        auditService.log(adminId, "REPORT_STATUS_UPDATED", "report", report.getId(),
                Map.of("status", status.name()));
        notifyReporterIfClosed(report);
        return toAdminResponse(report);
    }

    /** L auteur est prevenu quand la moderation tranche (RESOLVED ou DISMISSED), pas d une simple mise en examen. */
    private void notifyReporterIfClosed(Report report) {
        if (report.getStatus() != ReportStatus.RESOLVED && report.getStatus() != ReportStatus.DISMISSED) {
            return;
        }
        notificationService.notify(report.getReporter(), NotificationType.REPORT_RESOLVED,
                Map.of("reportId", report.getId().toString(), "status", report.getStatus().name(),
                        "resolutionNote", report.getResolutionNote() == null ? "" : report.getResolutionNote()));
    }

    private AdminReportResponse toAdminResponse(Report report) {
        User target = report.getReportedUser() != null ? report.getReportedUser()
                : (report.getReportedTrip() != null ? report.getReportedTrip().getDriver() : null);
        return new AdminReportResponse(report.getId(), parseReason(report.getReasonCode()), report.getStatus(),
                report.getDetails(), report.getCreatedAt(),
                toPersonRef(report.getReporter()), toPersonRef(target),
                report.getReportedTrip() != null ? report.getReportedTrip().getId() : null,
                report.getBookingId());
    }

    private AdminReportResponse.PersonRef toPersonRef(User user) {
        return user != null ? new AdminReportResponse.PersonRef(user.getId(), user.getFirstName(), user.getLastName()) : null;
    }

    /** {@code reports.reason_code} reste un texte libre en base (voir Report) ; retombe sur OTHER si non reconnu. */
    private ReportReason parseReason(String reasonCode) {
        if (reasonCode == null) return ReportReason.OTHER;
        try {
            return ReportReason.valueOf(reasonCode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ReportReason.OTHER;
        }
    }
}
