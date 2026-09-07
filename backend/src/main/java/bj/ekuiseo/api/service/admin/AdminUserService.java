package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.PhoneNumbers;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.admin.AdminUserDetailResponse;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.mapper.BookingMapper;
import bj.ekuiseo.api.mapper.TripMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.PaymentRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.NotificationService;
import bj.ekuiseo.api.service.RefreshTokenService;
import bj.ekuiseo.api.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion des utilisateurs cote back-office : recherche, fiche detaillee, suspension,
 * reactivation, correction de contact, retrait du badge d identite et anonymisation
 * (voir AdminVerificationService pour la file de moderation des dossiers d identite).
 *
 * <p>Phase 2 (constats F304/F305/F306) : un administrateur ne peut ni se suspendre
 * lui-meme ni suspendre / anonymiser un autre administrateur (409) ; la fiche complete
 * et ses sous-ressources paginees remplacent l ancien POST .../verify-identity, supprime
 * (un badge se pose uniquement par l approbation d un dossier, constat F604).</p>
 */
@Service
public class AdminUserService {

    /** Plafond de la liste (non paginee, voir GET /api/v1/admin/users?q=...) pour eviter un dump complet de la table. */
    private static final int SEARCH_LIMIT = 100;
    /** Taille maximale d une page de sous-ressource (reservations, trajets, paiements). */
    static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final BookingService bookingService;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final NotificationService notificationService;
    private final UserService userService;
    private final PaymentAccountRepository paymentAccountRepository;
    private final PaymentRepository paymentRepository;
    private final VehicleMapper vehicleMapper;
    private final BookingMapper bookingMapper;
    private final TripMapper tripMapper;

    public AdminUserService(UserRepository userRepository, VehicleRepository vehicleRepository,
                             TripRepository tripRepository, BookingRepository bookingRepository,
                             AuditService auditService, RefreshTokenService refreshTokenService,
                             BookingService bookingService, IdentityVerificationRepository identityVerificationRepository,
                             NotificationService notificationService, UserService userService,
                             PaymentAccountRepository paymentAccountRepository, PaymentRepository paymentRepository,
                             VehicleMapper vehicleMapper, BookingMapper bookingMapper, TripMapper tripMapper) {
        this.refreshTokenService = refreshTokenService;
        this.bookingService = bookingService;
        this.identityVerificationRepository = identityVerificationRepository;
        this.notificationService = notificationService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.auditService = auditService;
        this.paymentAccountRepository = paymentAccountRepository;
        this.paymentRepository = paymentRepository;
        this.vehicleMapper = vehicleMapper;
        this.bookingMapper = bookingMapper;
        this.tripMapper = tripMapper;
    }

    /**
     * Recherche libre (nom/prenom/telephone/e-mail), a plat et plafonnee (voir
     * SEARCH_LIMIT) plutot que paginee : le front (useAdminUsers) attend un
     * tableau simple, pas une Page. Une chaine vide renvoie les utilisateurs les
     * plus recents (voir UserRepository#search, tri par createdAt desc). Chaque
     * consultation est journalisee (ADMIN_USERS_SEARCHED : terme et nombre de
     * resultats, constat F520) : une liste de donnees personnelles a un lecteur.
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> search(UUID adminId, String q) {
        String term = q == null ? "" : q.trim();
        Page<User> page = userRepository.search(term, PageRequest.of(0, SEARCH_LIMIT));
        List<User> users = page.getContent();
        auditService.log(adminId, "ADMIN_USERS_SEARCHED", "user", null,
                Map.of("q", term, "resultCount", users.size()));
        if (users.isEmpty()) {
            return List.of();
        }
        // Deux requetes group by sur la liste d identifiants au lieu de deux count par
        // utilisateur (constats F016/F119/F308).
        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, Long> trips = new HashMap<>();
        for (TripRepository.IdCount c : tripRepository.countByDriverIds(ids)) {
            trips.put(c.getId(), c.getCount());
        }
        Map<UUID, Long> bookings = new HashMap<>();
        for (BookingRepository.IdCount c : bookingRepository.countByPassengerIds(ids)) {
            bookings.put(c.getId(), c.getCount());
        }
        return users.stream()
                .map(u -> toResponse(u, trips.getOrDefault(u.getId(), 0L), bookings.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    /** Fiche complete (GET /api/v1/admin/users/{id}), constats F305/F306. */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getDetail(UUID userId) {
        User u = findUser(userId);
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        AdminUserDetailResponse.Identity identity = verification == null ? null
                : new AdminUserDetailResponse.Identity(verification.getStatus(), verification.getDocumentType(),
                        last4(verification.getDocumentNumber()));
        List<AdminUserDetailResponse.PaymentAccountRef> accounts = paymentAccountRepository
                .findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(a -> new AdminUserDetailResponse.PaymentAccountRef(a.getId(), a.getProvider(), a.getPhone(),
                        a.isDefault(), a.getVerifiedAt() != null))
                .toList();
        return new AdminUserDetailResponse(u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(), u.getEmail(),
                u.getRole(), u.getStatus(), u.getSuspendedReason(), u.getSuspendedAt(), u.getCreatedAt(),
                identity, u.isIdentityVerified(),
                vehicleRepository.findByOwnerId(userId).stream().map(vehicleMapper::toResponse).toList(),
                accounts, tripRepository.countByDriverId(userId), bookingRepository.countByPassengerId(userId),
                u.getRatingAvg(), u.getLateCancellationsCount(), u.getDeletedAt(),
                u.isEmailVerified(), u.getLastLoginAt());
    }

    /** Reservations du compte en tant que passager, paginees (GET /api/v1/admin/users/{id}/bookings). */
    @Transactional(readOnly = true)
    public Page<BookingResponse> bookings(UUID userId, int page, int size) {
        findUser(userId);
        return bookingRepository.findByPassengerIdOrderByCreatedAtDesc(userId, pageable(page, size)).map(bookingMapper::toResponse);
    }

    /** Trajets publies par le compte en tant que conducteur, pagines (GET /api/v1/admin/users/{id}/trips). */
    @Transactional(readOnly = true)
    public Page<TripResponse> trips(UUID userId, int page, int size) {
        findUser(userId);
        return tripRepository.findByDriverIdOrderByDepartureAtDesc(userId, pageable(page, size)).map(tripMapper::toResponse);
    }

    /** Paiements du compte (reservations et abonnements), pagines (GET /api/v1/admin/users/{id}/payments). */
    @Transactional(readOnly = true)
    public Page<AdminPaymentResponse> payments(UUID userId, int page, int size) {
        User user = findUser(userId);
        return paymentRepository.findByUserId(userId, pageable(page, size)).map(p -> toPaymentResponse(p, user));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size)));
    }

    private static AdminPaymentResponse toPaymentResponse(Payment p, User user) {
        return new AdminPaymentResponse(p.getId(),
                p.getBooking() != null ? p.getBooking().getId() : null,
                p.getSubscription() != null ? p.getSubscription().getId() : null,
                user.getId(), user.getFirstName() + " " + user.getLastName(), user.getPhone(),
                p.getProviderTxId(), p.getAmount(), p.getStatus(), p.getRefundAmount(), p.getRefundReason(),
                p.getRefundRequestedAt(), p.getRefundAttempts(), p.getRefundLastError(), p.getRefundedAt(), p.getCreatedAt());
    }

    /** Quatre derniers caracteres d un numero de piece, jamais le numero entier dans une fiche. */
    static String last4(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) return null;
        String n = documentNumber.trim();
        return n.length() <= 4 ? n : n.substring(n.length() - 4);
    }

    /**
     * Suspension avec cascade (constat F039) : sessions revoquees, trajets a venir du
     * conducteur annules (passagers rembourses et prevenus, sans annulation tardive
     * comptee), navettes (modeles) fermees, et reservations actives du passager annulees
     * avec remboursement integral. Le nombre d elements touches est journalise.
     * Un administrateur ne se suspend pas lui-meme et ne suspend pas un autre
     * administrateur (409, constat F304) : retirer d abord le role.
     */
    @Transactional
    public AdminUserResponse suspend(UUID adminId, UUID userId, String reason) {
        User user = findUser(userId);
        assertNotSelfNorAdmin(adminId, user, "suspendre");
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ConflictException("Cet utilisateur est deja suspendu");
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ConflictException("Ce compte a ete supprime");
        }
        user.setStatus(UserStatus.SUSPENDED);
        user.setSuspendedReason(reason);
        user.setSuspendedAt(Instant.now());
        user = userRepository.save(user);
        // Plus aucune session ne doit se prolonger : le filtre JWT coupe les acces en cours
        // (statut verifie a chaque requete) et la revocation coupe les rafraichissements.
        refreshTokenService.revokeAll(userId);

        List<Trip> upcoming = tripRepository.findByDriverIdAndStatusInAndDepartureAtAfter(userId,
                List.of(TripStatus.PUBLISHED, TripStatus.FULL, TripStatus.DRAFT), Instant.now());
        List<Trip> templates = tripRepository.findByRecurrenceRuleIsNotNullAndParentTripIdIsNullAndStatus(TripStatus.TEMPLATE)
                .stream().filter(t -> t.getDriver().getId().equals(userId)).toList();
        for (Trip trip : upcoming) {
            trip.setStatus(TripStatus.CANCELLED);
            tripRepository.save(trip);
            bookingService.cascadeCancelForPlatform(trip, "SUSPENSION_CONDUCTEUR");
        }
        for (Trip template : templates) {
            template.setStatus(TripStatus.CANCELLED);
            tripRepository.save(template);
        }
        int bookingsCancelled = bookingService.cancelActiveBookingsForSuspendedPassenger(userId);

        auditService.log(adminId, "USER_SUSPENDED", "user", userId, Map.of("reason", reason,
                "tripsCancelled", upcoming.size(), "templatesClosed", templates.size(),
                "bookingsCancelled", bookingsCancelled));
        // L interesse n a plus acces a l application : e-mail et SMS (selon preferences) portent le motif (constat F212).
        notificationService.notifyCritical(user, NotificationType.ACCOUNT_SUSPENDED, Map.of("reason", reason));
        return toResponse(user);
    }

    /** 409 pour agir sur soi-meme ou sur un autre administrateur (constat F304). */
    private static void assertNotSelfNorAdmin(UUID adminId, User target, String verb) {
        if (target.getId().equals(adminId)) {
            throw new ConflictException("Vous ne pouvez pas vous " + verb + " vous-meme");
        }
        if (target.getRole() == Role.ADMIN) {
            throw new ConflictException("Impossible de " + verb + " un administrateur : retirez d abord son role");
        }
    }

    /** Reactivation d'un utilisateur suspendu. Expose sous deux chemins (voir AdminUserController) : /activate (historique) et /reinstate (contrat front). */
    @Transactional
    public AdminUserResponse activate(UUID adminId, UUID userId) {
        User user = findUser(userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setSuspendedReason(null);
        user.setSuspendedAt(null);
        user = userRepository.save(user);
        auditService.log(adminId, "USER_REACTIVATED", "user", userId, Map.of());
        return toResponse(user);
    }

    /**
     * Correction de contact par un administrateur (constat F537) : e-mail et/ou numero,
     * apres verification hors ligne de l identite du demandeur. Le nouveau contact repart
     * non verifie (le prochain code le validera), les sessions sont revoquees, l ancien et
     * le nouveau contact ainsi que le motif sont journalises.
     */
    @Transactional
    public AdminUserResponse updateContact(UUID adminId, UUID userId, String email, String phone, String reason) {
        User user = findUser(userId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        boolean changed = false;
        if (email != null && !email.isBlank() && !email.trim().equalsIgnoreCase(user.getEmail())) {
            String next = email.trim();
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(next, userId)) {
                throw new ConflictException("Cette adresse e-mail est deja utilisee par un autre compte");
            }
            details.put("previousEmail", user.getEmail() == null ? "" : user.getEmail());
            details.put("nextEmail", next);
            user.setEmail(next);
            user.setPendingEmail(null);
            user.setEmailVerified(false);
            changed = true;
        }
        if (phone != null && !phone.isBlank()) {
            String next = PhoneNumbers.normalize(phone);
            if (!next.equals(user.getPhone())) {
                if (userRepository.existsByPhoneAndIdNot(next, userId)) {
                    throw new ConflictException("Ce numero est deja utilise par un autre compte");
                }
                details.put("previousPhone", user.getPhone());
                details.put("nextPhone", next);
                user.setPhone(next);
                user.setPhoneVerified(false);
                changed = true;
            }
        }
        if (!changed) {
            throw new BadRequestException("Aucun changement : indiquez un nouvel e-mail ou un nouveau numero");
        }
        user = userRepository.save(user);
        refreshTokenService.revokeAll(userId);
        auditService.log(adminId, "USER_CONTACT_CHANGED", "user", userId, details);
        return toResponse(user);
    }

    /**
     * Retrait du badge "identite verifiee" (constat F601), qu il vienne d un dossier
     * approuve ou d une validation historique. Le dossier eventuel passe REJECTED avec le
     * motif, pour que l utilisateur puisse resoumettre ; il est prevenu (IDENTITY_REVOKED).
     */
    @Transactional
    public AdminUserResponse revokeIdentity(UUID adminId, UUID userId, String reason) {
        User user = findUser(userId);
        boolean hadBadge = user.isIdentityVerified();
        user.setIdentityVerified(false);
        user = userRepository.save(user);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("hadBadge", hadBadge);
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        if (verification != null && verification.getStatus() != IdentityVerificationStatus.REJECTED) {
            details.put("previousVerificationStatus", verification.getStatus().name());
            verification.setStatus(IdentityVerificationStatus.REJECTED);
            verification.setReviewedAt(Instant.now());
            verification.setReviewedBy(userRepository.findById(adminId).orElse(null));
            verification.setRejectionReason(reason);
            identityVerificationRepository.save(verification);
        }
        auditService.log(adminId, "USER_IDENTITY_REVOKED", "user", userId, details);
        notificationService.notify(user, NotificationType.IDENTITY_REVOKED, Map.of("reason", reason));
        return toResponse(user);
    }

    /**
     * Anonymisation par l administration (constat F507), voir {@link UserService#anonymize}.
     * Jamais sur soi-meme ni sur un administrateur (409, constat F304).
     */
    @Transactional
    public AdminUserResponse anonymize(UUID adminId, UUID userId, String reason) {
        assertNotSelfNorAdmin(adminId, findUser(userId), "anonymiser");
        userService.anonymize(userId, adminId, reason);
        return toResponse(findUser(userId));
    }

    @Transactional
    public void verifyVehicle(UUID adminId, UUID vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicule introuvable"));
        vehicle.setVerified(true);
        vehicleRepository.save(vehicle);
        auditService.log(adminId, "VEHICLE_VERIFIED", "vehicle", vehicleId, Map.of());
    }

    private User findUser(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }

    /** Vue d un seul utilisateur (actions unitaires) : deux comptes cibles, sans N+1 possible. */
    private AdminUserResponse toResponse(User u) {
        return toResponse(u, tripRepository.countByDriverId(u.getId()), bookingRepository.countByPassengerId(u.getId()));
    }

    private AdminUserResponse toResponse(User u, long tripsPublished, long bookingsMade) {
        return new AdminUserResponse(u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(), u.getEmail(),
                u.getCreatedAt(), u.isIdentityVerified(), u.isPhoneVerified(), u.isEmailVerified(),
                u.getStatus() == UserStatus.SUSPENDED, tripsPublished, bookingsMade, u.getRatingAvg(), u.getDeletedAt(),
                u.getRole() == null ? Role.USER : u.getRole(), u.getLastLoginAt());
    }
}
