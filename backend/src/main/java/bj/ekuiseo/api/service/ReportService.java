package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
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
import bj.ekuiseo.api.dto.report.NoShowDecisionRequest;
import bj.ekuiseo.api.dto.report.ReportResponse;
import bj.ekuiseo.api.dto.report.ResolveReportRequest;
import bj.ekuiseo.api.mapper.ReportMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.ConversationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
 *
 * <p>Phase 2 (constats F549/F550/F552/F301/F307) : la cible est prevenue qu un signalement
 * la concerne (REPORT_RECEIVED, sans l identite de l auteur) ; les transitions sont
 * OPEN -> IN_REVIEW -> RESOLVED | DISMISSED, rien d autre (409) ; une note est obligatoire
 * pour clore, et {@code resolvedAt} / {@code resolvedBy} ne sont poses qu a la cloture ;
 * le nombre d autres signalements visant la meme personne est expose ; les conversations
 * liant les deux parties sont consultables par la moderation, chaque consultation etant
 * journalisee (ADMIN_CONVERSATION_VIEWED).</p>
 */
@Service
public class ReportService {

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
    private static final List<BookingStatus> ALL_BOOKING_STATUSES = Arrays.asList(BookingStatus.values());

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ReportMapper reportMapper;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final BookingService bookingService;

    public ReportService(ReportRepository reportRepository, UserRepository userRepository,
                          TripRepository tripRepository, BookingRepository bookingRepository,
                          ConversationRepository conversationRepository, MessageRepository messageRepository,
                          ReportMapper reportMapper, AuditService auditService,
                          NotificationService notificationService, BookingService bookingService) {
        this.bookingService = bookingService;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.reportMapper = reportMapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest req) {
        User reporter = userRepository.findById(reporterId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        ReportReason reason = req.reasonCode();
        Report.ReportBuilder builder = Report.builder().reporter(reporter).reasonCode(reason.name()).details(req.details());

        Booking link;
        User target;
        if (req.reportedUserId() != null) {
            if (req.reportedUserId().equals(reporterId)) {
                throw new BadRequestException("Vous ne pouvez pas vous signaler vous-meme");
            }
            target = userRepository.findById(req.reportedUserId())
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
            target = trip.getDriver();
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
                Map.of("reasonCode", reason.name(), "bookingId", link != null ? link.getId().toString() : ""));
        // Constat F550 : la personne visee sait qu un signalement la concerne (motif seul,
        // jamais l identite ni les details de l auteur), pour pouvoir apporter sa version.
        if (target != null) {
            notificationService.notify(target, NotificationType.REPORT_RECEIVED,
                    NotificationTemplates.payload("reportId", report.getId().toString(), "reason", reason.name()));
        }
        return reportMapper.toResponse(report);
    }

    @Transactional(readOnly = true)
    public Page<ReportResponse> list(ReportStatus status, Pageable pageable) {
        Page<Report> page = status != null ? reportRepository.findByStatus(status, pageable) : reportRepository.findAll(pageable);
        return page.map(reportMapper::toResponse);
    }

    /** POST /api/v1/admin/reports/{id}/resolve : memes transitions que {@link #updateStatus}, avec la note. */
    @Transactional
    public ReportResponse resolve(UUID adminId, UUID reportId, ResolveReportRequest req) {
        Report report = transition(adminId, reportId, req.status(), req.resolutionNote(), "REPORT_RESOLVED");
        return reportMapper.toResponse(report);
    }

    /**
     * Vue back-office a plat, GET /api/v1/admin/reports?status=... (le front
     * attend un tableau simple, pas une Page), du plus recent au plus ancien.
     * Paginee cote serveur (constat F237), du plus recent au plus ancien, taille bornee par
     * {@link Paging#MAX_PAGE_SIZE}. Les parties (signalant, cible, conducteur du trajet
     * signale) sont chargees avec les signalements (EntityGraph, constat F119) : une requete
     * par page, pas N+1.
     */
    @Transactional(readOnly = true)
    public Page<AdminReportResponse> listForAdmin(ReportStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by("createdAt").descending());
        Page<Report> found = status != null
                ? reportRepository.findByStatusWithParties(status, pageable)
                : reportRepository.findAllWithParties(pageable);
        return found.map(this::toAdminResponse);
    }

    /** PATCH /api/v1/admin/reports/{id} : changement d etat cible (note obligatoire pour clore). */
    @Transactional
    public AdminReportResponse updateStatus(UUID adminId, UUID reportId, ReportStatus status, String resolutionNote) {
        Report report = transition(adminId, reportId, status, resolutionNote, "REPORT_STATUS_UPDATED");
        return toAdminResponse(report);
    }

    /**
     * POST /api/v1/admin/reports/{id}/no-show-decision (V25) : la moderation tranche un dossier
     * « conducteur absent ». Le signalement doit etre un NO_SHOW lie a une reservation ; tout le
     * travail (remboursement ou remise en reversement, cloture du signalement, notifications)
     * est fait par BookingService#resolveDriverNoShow, une seule source pour l automatique et
     * le manuel.
     */
    @Transactional
    public AdminReportResponse decideNoShow(UUID adminId, UUID reportId, NoShowDecisionRequest req) {
        Report report = reportRepository.findById(reportId).orElseThrow(() -> new NotFoundException("Signalement introuvable"));
        if (!ReportReason.NO_SHOW.name().equals(report.getReasonCode()) || report.getBookingId() == null) {
            throw new BadRequestException("Ce signalement n est pas un dossier « conducteur absent » lie a une reservation");
        }
        bookingService.resolveDriverNoShow(adminId, report.getBookingId(), req.decision(), req.note());
        return toAdminResponse(reportRepository.findById(reportId).orElse(report));
    }

    /** Variante historique sans note (IN_REVIEW seulement : clore exige une note). */
    @Transactional
    public AdminReportResponse updateStatus(UUID adminId, UUID reportId, ReportStatus status) {
        return updateStatus(adminId, reportId, status, null);
    }

    /**
     * Transition d etat (constats F549/F552) : OPEN -> IN_REVIEW, IN_REVIEW -> RESOLVED ou
     * DISMISSED ; tout autre passage (retour en arriere, saut d etape, signalement deja clos,
     * meme etat) est un 409. Clore exige une note ; {@code resolvedAt} / {@code resolvedBy}
     * ne sont poses qu a la cloture. L auteur est prevenu de l issue.
     */
    private Report transition(UUID adminId, UUID reportId, ReportStatus next, String resolutionNote, String auditAction) {
        Report report = reportRepository.findById(reportId).orElseThrow(() -> new NotFoundException("Signalement introuvable"));
        if (next == null) {
            throw new BadRequestException("Le nouvel etat est obligatoire");
        }
        ReportStatus current = report.getStatus();
        if (!isAllowedTransition(current, next)) {
            throw new ConflictException("Passage " + current + " -> " + next + " impossible : un signalement suit "
                    + "OPEN -> IN_REVIEW -> RESOLVED ou DISMISSED");
        }
        boolean closing = next == ReportStatus.RESOLVED || next == ReportStatus.DISMISSED;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("previousStatus", current.name());
        details.put("status", next.name());
        report.setStatus(next);
        if (closing) {
            if (resolutionNote == null || resolutionNote.isBlank()) {
                throw new BadRequestException("Une note de resolution est obligatoire pour clore un signalement");
            }
            report.setResolutionNote(resolutionNote.trim());
            report.setResolvedBy(userRepository.findById(adminId).orElse(null));
            report.setResolvedAt(Instant.now());
            details.put("resolutionNote", resolutionNote.trim());
        }
        report = reportRepository.save(report);
        auditService.log(adminId, auditAction, "report", report.getId(), details);
        notifyReporterIfClosed(report);
        return report;
    }

    static boolean isAllowedTransition(ReportStatus current, ReportStatus next) {
        if (current == ReportStatus.OPEN) {
            return next == ReportStatus.IN_REVIEW;
        }
        if (current == ReportStatus.IN_REVIEW) {
            return next == ReportStatus.RESOLVED || next == ReportStatus.DISMISSED;
        }
        return false;
    }

    /**
     * Conversations liant l auteur et la personne visee (constat F549) : celle de la
     * reservation du signalement, et celles de toutes les reservations partagees entre les
     * deux parties (dans les deux sens, tous statuts). Chaque conversation lue est
     * journalisee (ADMIN_CONVERSATION_VIEWED, avec l identifiant du signalement).
     */
    @Transactional(readOnly = true)
    public List<AdminReportConversationResponse> conversations(UUID adminId, UUID reportId) {
        Report report = reportRepository.findById(reportId).orElseThrow(() -> new NotFoundException("Signalement introuvable"));
        User target = resolveTarget(report);
        Set<UUID> bookingIds = new LinkedHashSet<>();
        if (report.getBookingId() != null) {
            bookingIds.add(report.getBookingId());
        }
        if (target != null) {
            bookingRepository.findSharedBookings(report.getReporter().getId(), target.getId(), ALL_BOOKING_STATUSES)
                    .forEach(b -> bookingIds.add(b.getId()));
        }
        if (bookingIds.isEmpty()) {
            return List.of();
        }
        List<AdminReportConversationResponse> result = new ArrayList<>();
        for (Conversation conversation : conversationRepository.findByBookingIdIn(bookingIds)) {
            Booking booking = conversation.getBooking();
            Trip trip = booking.getTrip();
            List<AdminReportConversationResponse.MessageItem> messages = messageRepository
                    .findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                    .map(this::toMessageItem).toList();
            result.add(new AdminReportConversationResponse(conversation.getId(), booking.getId(), trip.getId(),
                    List.of(toParticipant(booking.getPassenger()), toParticipant(trip.getDriver())), messages));
            auditService.log(adminId, "ADMIN_CONVERSATION_VIEWED", "conversation", conversation.getId(),
                    Map.of("reportId", reportId.toString(), "bookingId", booking.getId().toString(),
                            "messages", messages.size()));
        }
        return result;
    }

    private AdminReportConversationResponse.MessageItem toMessageItem(Message m) {
        return new AdminReportConversationResponse.MessageItem(m.getId(), m.getSender().getId(), m.getBody(), m.getCreatedAt());
    }

    private AdminReportConversationResponse.Participant toParticipant(User user) {
        return new AdminReportConversationResponse.Participant(user.getId(), user.getFirstName(), user.getLastName());
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

    /** La personne visee : l utilisateur signale, sinon le conducteur du trajet signale. */
    private static User resolveTarget(Report report) {
        if (report.getReportedUser() != null) {
            return report.getReportedUser();
        }
        return report.getReportedTrip() != null ? report.getReportedTrip().getDriver() : null;
    }

    private AdminReportResponse toAdminResponse(Report report) {
        User target = resolveTarget(report);
        long priors = target == null ? 0L : reportRepository.countOthersAgainstTarget(report.getId(), target.getId());
        // detail jamais null (constat F503) : le front le type en chaine.
        return new AdminReportResponse(report.getId(), ReportReason.valueOf(report.getReasonCode()), report.getStatus(),
                report.getDetails() == null ? "" : report.getDetails(), report.getCreatedAt(),
                toPersonRef(report.getReporter()), toPersonRef(target),
                report.getReportedTrip() != null ? report.getReportedTrip().getId() : null,
                report.getBookingId(), report.getResolutionNote(),
                report.getResolvedBy() != null ? report.getResolvedBy().getId() : null,
                report.getResolvedAt(), priors, noShowDispute(report));
    }

    /**
     * Dossier « conducteur absent » (V25) : ce que la moderation doit voir avant de trancher -
     * l acompte en jeu, l echeance du remboursement automatique, la version du conducteur s il a
     * conteste, et l issue. Null pour tout autre signalement.
     */
    private AdminReportResponse.NoShowDispute noShowDispute(Report report) {
        if (!ReportReason.NO_SHOW.name().equals(report.getReasonCode()) || report.getBookingId() == null) {
            return null;
        }
        return bookingRepository.findById(report.getBookingId())
                .filter(b -> b.getPassengerConfirmation() == bj.ekuiseo.api.domain.enums.PassengerConfirmation.DRIVER_NO_SHOW)
                .map(b -> new AdminReportResponse.NoShowDispute(b.getId(), b.getStatus(), b.getPaymentMethod(),
                        b.getDepositAmount(), b.getDriverNoShowRefundDueAt(), b.getDriverNoShowContestedAt(),
                        b.getDriverNoShowContestDetails(), b.getDriverNoShowResolution(), b.getDriverNoShowResolvedAt(),
                        b.getDriverNoShowResolvedBy()))
                .orElse(null);
    }

    private AdminReportResponse.PersonRef toPersonRef(User user) {
        return user != null ? new AdminReportResponse.PersonRef(user.getId(), user.getFirstName(), user.getLastName()) : null;
    }
}
