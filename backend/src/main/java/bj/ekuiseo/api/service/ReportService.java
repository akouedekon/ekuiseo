package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.ReportReason;
import bj.ekuiseo.api.domain.enums.ReportStatus;
import bj.ekuiseo.api.dto.report.AdminReportResponse;
import bj.ekuiseo.api.dto.report.CreateReportRequest;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.mapper.ReportMapper;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Signalements d'utilisateurs ou de trajets (regle metier n.15), et leur moderation cote admin. */
@Service
public class ReportService {

    /** Plafond de la liste admin (a plat, non paginee, voir GET /api/v1/admin/reports). */
    private static final int ADMIN_LIST_LIMIT = 200;

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final ReportMapper reportMapper;
    private final AuditService auditService;

    public ReportService(ReportRepository reportRepository, UserRepository userRepository,
                          TripRepository tripRepository, ReportMapper reportMapper, AuditService auditService) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.reportMapper = reportMapper;
        this.auditService = auditService;
    }

    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest req) {
        User reporter = userRepository.findById(reporterId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        Report.ReportBuilder builder = Report.builder().reporter(reporter).reasonCode(req.reasonCode()).details(req.details());

        if (req.reportedUserId() != null) {
            if (req.reportedUserId().equals(reporterId)) {
                throw new BadRequestException("Vous ne pouvez pas vous signaler vous-meme");
            }
            User target = userRepository.findById(req.reportedUserId())
                    .orElseThrow(() -> new NotFoundException("Utilisateur signale introuvable"));
            builder.reportedUser(target);
        } else if (req.reportedTripId() != null) {
            Trip trip = tripRepository.findById(req.reportedTripId())
                    .orElseThrow(() -> new NotFoundException("Trajet signale introuvable"));
            builder.reportedTrip(trip);
        } else {
            throw new BadRequestException("Une cible (utilisateur ou trajet) doit etre precisee");
        }

        Report report = reportRepository.save(builder.build());
        auditService.log(reporterId, "REPORT_CREATED", "report", report.getId(),
                java.util.Map.of("reasonCode", req.reasonCode()));
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
                java.util.Map.of("status", req.status().name()));
        return reportMapper.toResponse(report);
    }

    /**
     * Vue back-office a plat, GET /api/v1/admin/reports?status=... (le front
     * attend un tableau simple, pas une Page). Plafonnee a ADMIN_LIST_LIMIT
     * plutot que veritablement paginee : la file de moderation est censee rester
     * courte (les signalements traites en sortent au fil de l'eau).
     */
    @Transactional(readOnly = true)
    public java.util.List<AdminReportResponse> listForAdmin(ReportStatus status) {
        Pageable pageable = PageRequest.of(0, ADMIN_LIST_LIMIT);
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
                java.util.Map.of("status", status.name()));
        return toAdminResponse(report);
    }

    private AdminReportResponse toAdminResponse(Report report) {
        User target = report.getReportedUser() != null ? report.getReportedUser()
                : (report.getReportedTrip() != null ? report.getReportedTrip().getDriver() : null);
        return new AdminReportResponse(report.getId(), parseReason(report.getReasonCode()), report.getStatus(),
                report.getDetails(), report.getCreatedAt(),
                toPersonRef(report.getReporter()), toPersonRef(target),
                report.getReportedTrip() != null ? report.getReportedTrip().getId() : null);
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
