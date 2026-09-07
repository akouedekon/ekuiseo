package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.domain.Booking;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.DriverSubscription;
import bj.ekuiseo.api.domain.IdentityDocument;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.Message;
import bj.ekuiseo.api.domain.Notification;
import bj.ekuiseo.api.domain.PaymentAccount;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.PushSubscription;
import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.domain.Review;
import bj.ekuiseo.api.domain.SearchAlert;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.TripStop;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.DriverSubscriptionRepository;
import bj.ekuiseo.api.repository.IdentityDocumentRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.PushSubscriptionRepository;
import bj.ekuiseo.api.repository.ReportRepository;
import bj.ekuiseo.api.repository.ReviewRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.TripStopRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Export des donnees personnelles (droit d acces, constat F508) : GET /api/v1/me/export
 * renvoie un document JSON construit ici, section par section, sans jamais inclure le
 * hash du mot de passe, le numero complet de la piece d identite ni un numero de compte
 * mobile money en clair. Un export par {@code ekuiseo.export.cooldown-hours} (429 sinon),
 * date conservee dans {@code users.last_export_at} (V16) ; chaque export est journalise.
 */
@Service
public class UserDataExportService {

    public static final String FILE_NAME = "ekuiseo-mes-donnees.json";

    private final UserRepository userRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final VehicleRepository vehicleRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final ReviewRepository reviewRepository;
    private final MessageRepository messageRepository;
    private final NotificationRepository notificationRepository;
    private final SearchAlertRepository searchAlertRepository;
    private final ReportRepository reportRepository;
    private final AuditService auditService;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final IdentityDocumentRepository identityDocumentRepository;
    private final Duration cooldown;

    public UserDataExportService(UserRepository userRepository, UserPreferencesRepository userPreferencesRepository,
                                 VehicleRepository vehicleRepository, PaymentAccountRepository paymentAccountRepository,
                                 IdentityVerificationRepository identityVerificationRepository,
                                 DriverSubscriptionRepository driverSubscriptionRepository, TripRepository tripRepository,
                                 TripStopRepository tripStopRepository, BookingRepository bookingRepository,
                                 PaymentRepository paymentRepository, DriverPayoutRepository driverPayoutRepository,
                                 ReviewRepository reviewRepository, MessageRepository messageRepository,
                                 NotificationRepository notificationRepository, SearchAlertRepository searchAlertRepository,
                                 ReportRepository reportRepository, AuditService auditService,
                                 PushSubscriptionRepository pushSubscriptionRepository,
                                 IdentityDocumentRepository identityDocumentRepository,
                                 @Value("${ekuiseo.export.cooldown-hours:24}") long cooldownHours) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.identityDocumentRepository = identityDocumentRepository;
        this.userRepository = userRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.vehicleRepository = vehicleRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.identityVerificationRepository = identityVerificationRepository;
        this.driverSubscriptionRepository = driverSubscriptionRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.reviewRepository = reviewRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
        this.searchAlertRepository = searchAlertRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
        this.cooldown = Duration.ofHours(cooldownHours);
    }

    /** Document complet ; 429 si un export a deja ete produit dans la fenetre. */
    @Transactional
    public Map<String, Object> export(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        Instant now = Instant.now();
        if (user.getLastExportAt() != null && user.getLastExportAt().plus(cooldown).isAfter(now)) {
            throw new TooManyRequestsException("Un export par " + cooldown.toHours() + " heures : reessayez a partir du "
                    + BookingService.formatLocal(user.getLastExportAt().plus(cooldown)));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("exportedAt", now.toString());
        doc.put("format", "ekuiseo-export-v1");
        doc.put("user", userSection(user));
        doc.put("preferences", userPreferencesRepository.findByUserId(userId).map(this::preferences).orElse(null));
        doc.put("vehicles", vehicleRepository.findByOwnerId(userId).stream().map(this::vehicle).toList());
        doc.put("paymentAccounts", paymentAccountRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::paymentAccount).toList());
        doc.put("identityVerification", identityVerificationRepository.findByUserId(userId).map(this::identity).orElse(null));
        doc.put("subscriptions", driverSubscriptionRepository.findByDriverIdOrderByCreatedAtDesc(userId).stream().map(this::subscription).toList());
        doc.put("trips", trips(userId));
        doc.put("bookings", bookingRepository.findByPassengerIdWithTripFetched(userId).stream().map(this::booking).toList());
        doc.put("payments", paymentRepository.findAllByUserId(userId).stream().map(this::payment).toList());
        doc.put("payouts", driverPayoutRepository.findByDriverIdOrderByRequestedAtAsc(userId).stream().map(this::payout).toList());
        doc.put("reviewsWritten", reviewRepository.findByAuthorIdOrderByCreatedAtDesc(userId).stream().map(this::review).toList());
        doc.put("reviewsReceived", reviewRepository.findByTargetIdOrderByCreatedAtDesc(userId).stream().map(this::review).toList());
        doc.put("messagesSent", messageRepository.findBySenderIdOrderByCreatedAtAsc(userId).stream().map(this::message).toList());
        doc.put("notifications", notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::notification).toList());
        doc.put("searchAlerts", searchAlertRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::alert).toList());
        doc.put("reportsFiled", reportRepository.findByReporterIdOrderByCreatedAtDesc(userId).stream().map(this::report).toList());
        doc.put("pushSubscriptions", pushSubscriptionRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::pushSubscription).toList());

        user.setLastExportAt(now);
        userRepository.save(user);
        auditService.log(userId, "USER_DATA_EXPORTED", "user", userId, Map.of("format", "ekuiseo-export-v1"));
        return doc;
    }

    private Map<String, Object> userSection(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(u.getId()));
        m.put("phone", u.getPhone());
        m.put("email", u.getEmail());
        m.put("firstName", u.getFirstName());
        m.put("lastName", u.getLastName());
        m.put("photoUrl", u.getPhotoUrl());
        m.put("bio", u.getBio());
        m.put("birthDate", str(u.getBirthDate()));
        m.put("genderPrefNote", u.getGenderPrefNote());
        m.put("phoneVerified", u.isPhoneVerified());
        m.put("emailVerified", u.isEmailVerified());
        m.put("identityVerified", u.isIdentityVerified());
        m.put("ratingAvg", u.getRatingAvg());
        m.put("ratingCount", u.getRatingCount());
        m.put("status", str(u.getStatus()));
        m.put("role", str(u.getRole()));
        m.put("lateCancellationsCount", u.getLateCancellationsCount());
        m.put("termsVersion", u.getTermsVersion());
        m.put("termsAcceptedAt", str(u.getTermsAcceptedAt()));
        m.put("createdAt", str(u.getCreatedAt()));
        m.put("updatedAt", str(u.getUpdatedAt()));
        return m;
    }

    private Map<String, Object> preferences(UserPreferences p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notifyByPush", p.isNotifyByPush());
        m.put("notifyBySms", p.isNotifyBySms());
        m.put("notifyByEmail", p.isNotifyByEmail());
        m.put("language", p.getLanguage());
        m.put("smoking", p.isSmoking());
        m.put("music", p.isMusic());
        m.put("pets", p.isPets());
        m.put("chatty", str(p.getChatty()));
        return m;
    }

    private Map<String, Object> vehicle(Vehicle v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(v.getId()));
        m.put("brand", v.getBrand());
        m.put("model", v.getModel());
        m.put("color", v.getColor());
        m.put("plate", v.getPlate());
        m.put("seats", v.getSeats());
        m.put("comfortLevel", str(v.getComfortLevel()));
        m.put("verified", v.isVerified());
        m.put("createdAt", str(v.getCreatedAt()));
        return m;
    }

    /** Numero de compte masque : la possession du numero est deja connue de l utilisateur, pas besoin de le faire circuler en clair. */
    private Map<String, Object> paymentAccount(PaymentAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(a.getId()));
        m.put("provider", str(a.getProvider()));
        m.put("phone", Masking.phone(a.getPhone()));
        m.put("label", a.getLabel());
        m.put("isDefault", a.isDefault());
        m.put("verified", a.getVerifiedAt() != null);
        m.put("createdAt", str(a.getCreatedAt()));
        return m;
    }

    /**
     * Statut, type et dates seulement : jamais le numero de la piece (constat F508). Les pieces
     * televersees (V20) apparaissent par leur presence (face, type, taille, date) : le contenu
     * chiffre n est pas joint a l export, l utilisateur en detient l original.
     */
    private Map<String, Object> identity(IdentityVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", str(v.getStatus()));
        m.put("documentType", str(v.getDocumentType()));
        m.put("submittedAt", str(v.getSubmittedAt()));
        m.put("reviewedAt", str(v.getReviewedAt()));
        m.put("rejectionReason", v.getRejectionReason());
        m.put("documents", identityDocumentRepository.findByVerificationIdOrderBySideAsc(v.getId()).stream()
                .map(this::identityDocument).toList());
        return m;
    }

    private Map<String, Object> identityDocument(IdentityDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("side", str(d.getSide()));
        m.put("contentType", d.getContentType());
        m.put("sizeBytes", d.getSizeBytes());
        m.put("sha256", d.getSha256());
        m.put("createdAt", str(d.getCreatedAt()));
        return m;
    }

    /** Abonnement push : origine du service push et dates ; les cles de chiffrement et l endpoint complet ne sortent pas. */
    private Map<String, Object> pushSubscription(PushSubscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(s.getId()));
        m.put("pushService", pushServiceOrigin(s.getEndpoint()));
        m.put("userAgent", s.getUserAgent());
        m.put("createdAt", str(s.getCreatedAt()));
        m.put("lastUsedAt", str(s.getLastUsedAt()));
        return m;
    }

    private static String pushServiceOrigin(String endpoint) {
        if (endpoint == null) return null;
        int scheme = endpoint.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = endpoint.indexOf('/', start);
        return slash < 0 ? endpoint : endpoint.substring(0, slash);
    }

    private Map<String, Object> subscription(DriverSubscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(s.getId()));
        m.put("priceFcfa", s.getPriceFcfa());
        m.put("status", str(s.getStatus()));
        m.put("startedAt", str(s.getStartedAt()));
        m.put("currentPeriodEnd", str(s.getCurrentPeriodEnd()));
        m.put("createdAt", str(s.getCreatedAt()));
        return m;
    }

    private List<Map<String, Object>> trips(UUID driverId) {
        List<Trip> trips = tripRepository.findByDriverIdOrderByDepartureAtDesc(driverId);
        Map<UUID, List<TripStop>> stopsByTrip = new HashMap<>();
        if (!trips.isEmpty()) {
            for (TripStop stop : tripStopRepository.findByTripIdInOrderByPositionAsc(trips.stream().map(Trip::getId).toList())) {
                stopsByTrip.computeIfAbsent(stop.getTrip().getId(), k -> new ArrayList<>()).add(stop);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(trips.size());
        for (Trip t : trips) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", str(t.getId()));
            m.put("tripType", str(t.getTripType()));
            m.put("originLabel", t.getOriginLabel());
            m.put("originLat", t.getOriginLat());
            m.put("originLng", t.getOriginLng());
            m.put("destLabel", t.getDestLabel());
            m.put("destLat", t.getDestLat());
            m.put("destLng", t.getDestLng());
            m.put("departureAt", str(t.getDepartureAt()));
            m.put("seatsTotal", t.getSeatsTotal());
            m.put("seatsAvailable", t.getSeatsAvailable());
            m.put("pricePerSeat", t.getPricePerSeat());
            m.put("instantBooking", t.isInstantBooking());
            m.put("luggagePolicy", t.getLuggagePolicy());
            m.put("description", t.getDescription());
            m.put("status", str(t.getStatus()));
            m.put("recurrenceRule", t.getRecurrenceRule());
            m.put("parentTripId", str(t.getParentTripId()));
            m.put("createdAt", str(t.getCreatedAt()));
            m.put("stops", stopsByTrip.getOrDefault(t.getId(), List.of()).stream().map(this::stop).toList());
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> stop(TripStop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(s.getId()));
        m.put("position", s.getPosition());
        m.put("label", s.getLabel());
        m.put("lat", s.getLat());
        m.put("lng", s.getLng());
        m.put("plannedAt", str(s.getPlannedAt()));
        m.put("priceFromOrigin", s.getPriceFromOrigin());
        return m;
    }

    private Map<String, Object> booking(Booking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(b.getId()));
        m.put("tripId", str(b.getTrip().getId()));
        m.put("route", b.getTrip().getOriginLabel() + " -> " + b.getTrip().getDestLabel());
        m.put("departureAt", str(b.getTrip().getDepartureAt()));
        m.put("seats", b.getSeats());
        m.put("pickupStopId", str(b.getPickupStopId()));
        m.put("dropoffStopId", str(b.getDropoffStopId()));
        m.put("status", str(b.getStatus()));
        m.put("createdAt", str(b.getCreatedAt()));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("paymentMethod", str(b.getPaymentMethod()));
        plan.put("totalAmountFcfa", b.getAmount());
        plan.put("serviceFeeFcfa", b.getServiceFee());
        plan.put("depositAmountFcfa", b.getDepositAmount());
        plan.put("balanceDueOnBoardFcfa", b.getBalanceDueOnBoard());
        plan.put("expiresAt", str(b.getExpiresAt()));
        plan.put("approvalDeadlineAt", str(b.getApprovalDeadlineAt()));
        plan.put("freeCancellationUntil", str(b.getFreeCancellationUntil()));
        m.put("paymentPlan", plan);
        return m;
    }

    private Map<String, Object> payment(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(p.getId()));
        m.put("bookingId", p.getBooking() != null ? str(p.getBooking().getId()) : null);
        m.put("subscriptionId", p.getSubscription() != null ? str(p.getSubscription().getId()) : null);
        m.put("provider", str(p.getProvider()));
        m.put("providerTxId", p.getProviderTxId());
        m.put("amountFcfa", p.getAmount());
        m.put("feeFcfa", p.getFee());
        m.put("channel", str(p.getChannel()));
        m.put("status", str(p.getStatus()));
        m.put("refundAmountFcfa", p.getRefundAmount());
        m.put("refundReason", p.getRefundReason());
        m.put("refundRequestedAt", str(p.getRefundRequestedAt()));
        m.put("refundedAt", str(p.getRefundedAt()));
        m.put("createdAt", str(p.getCreatedAt()));
        return m;
    }

    private Map<String, Object> payout(DriverPayout p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(p.getId()));
        m.put("amountFcfa", p.getAmount());
        m.put("status", str(p.getStatus()));
        m.put("destinationProvider", str(p.getDestinationProvider()));
        m.put("destination", Masking.phone(p.getDestinationMsisdn()));
        m.put("periodStart", str(p.getPeriodStart()));
        m.put("periodEnd", str(p.getPeriodEnd()));
        m.put("requestedAt", str(p.getRequestedAt()));
        m.put("settledAt", str(p.getSettledAt()));
        m.put("settledAmountFcfa", p.getSettledAmount());
        m.put("externalReference", p.getExternalReference());
        m.put("failureReason", p.getFailureReason());
        return m;
    }

    private Map<String, Object> review(Review r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(r.getId()));
        m.put("tripId", str(r.getTrip().getId()));
        m.put("authorId", str(r.getAuthor().getId()));
        m.put("targetId", str(r.getTarget().getId()));
        m.put("role", str(r.getRole()));
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("createdAt", str(r.getCreatedAt()));
        return m;
    }

    private Map<String, Object> message(Message msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(msg.getId()));
        m.put("conversationId", str(msg.getConversation().getId()));
        m.put("body", msg.getBody());
        m.put("readAt", str(msg.getReadAt()));
        m.put("createdAt", str(msg.getCreatedAt()));
        return m;
    }

    private Map<String, Object> notification(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(n.getId()));
        m.put("type", str(n.getType()));
        m.put("payload", n.getPayload());
        m.put("readAt", str(n.getReadAt()));
        m.put("createdAt", str(n.getCreatedAt()));
        return m;
    }

    private Map<String, Object> alert(SearchAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(a.getId()));
        m.put("originLabel", a.getOriginLabel());
        m.put("originLat", a.getOriginLat());
        m.put("originLng", a.getOriginLng());
        m.put("destLabel", a.getDestLabel());
        m.put("destLat", a.getDestLat());
        m.put("destLng", a.getDestLng());
        m.put("dateFrom", str(a.getDateFrom()));
        m.put("dateTo", str(a.getDateTo()));
        m.put("seats", a.getSeats());
        m.put("tripType", str(a.getTripType()));
        m.put("radiusKm", a.getRadiusKm());
        m.put("active", a.isActive());
        m.put("createdAt", str(a.getCreatedAt()));
        return m;
    }

    private Map<String, Object> report(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(r.getId()));
        m.put("reportedUserId", r.getReportedUser() != null ? str(r.getReportedUser().getId()) : null);
        m.put("reportedTripId", r.getReportedTrip() != null ? str(r.getReportedTrip().getId()) : null);
        m.put("reasonCode", r.getReasonCode());
        m.put("details", r.getDetails());
        m.put("status", str(r.getStatus()));
        m.put("resolutionNote", r.getResolutionNote());
        m.put("createdAt", str(r.getCreatedAt()));
        m.put("resolvedAt", str(r.getResolvedAt()));
        return m;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
