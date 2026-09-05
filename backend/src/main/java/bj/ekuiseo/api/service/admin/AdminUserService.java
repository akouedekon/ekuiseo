package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.PhoneNumbers;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.Trip;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.domain.enums.NotificationType;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion des utilisateurs cote back-office : recherche, suspension,
 * reactivation, et validation des verifications d'identite (voir aussi
 * AdminVerificationService pour la file de moderation dediee, plus complete).
 *
 * <p><b>Limitation connue</b> : aucun sous-systeme de stockage de documents
 * d'identite (upload de piece d'identite, selfie) n'est implemente dans ce
 * squelette (voir README "Ce qui reste a faire") ; {@link #verifyIdentity} se
 * contente donc de positionner le badge identity_verified, en supposant qu'un
 * processus hors-ligne (verification manuelle) a deja eu lieu.</p>
 */
@Service
public class AdminUserService {

    /** Plafond de la liste (non paginee, voir GET /api/v1/admin/users?q=...) pour eviter un dump complet de la table. */
    private static final int SEARCH_LIMIT = 100;

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

    public AdminUserService(UserRepository userRepository, VehicleRepository vehicleRepository,
                             TripRepository tripRepository, BookingRepository bookingRepository,
                             AuditService auditService, RefreshTokenService refreshTokenService,
                             BookingService bookingService, IdentityVerificationRepository identityVerificationRepository,
                             NotificationService notificationService, UserService userService) {
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
    }

    /**
     * Recherche libre (nom/prenom/telephone/e-mail), a plat et plafonnee (voir
     * SEARCH_LIMIT) plutot que paginee : le front (useAdminUsers) attend un
     * tableau simple, pas une Page. Une chaine vide renvoie les utilisateurs les
     * plus recents (voir UserRepository#search, tri par createdAt desc).
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> search(String q) {
        Page<User> page = userRepository.search(q == null ? "" : q.trim(), PageRequest.of(0, SEARCH_LIMIT));
        return page.getContent().stream().map(this::toResponse).toList();
    }

    /**
     * Suspension avec cascade (constat F039) : sessions revoquees, trajets a venir du
     * conducteur annules (passagers rembourses et prevenus, sans annulation tardive
     * comptee), navettes (modeles) fermees, et reservations actives du passager annulees
     * avec remboursement integral. Le nombre d elements touches est journalise.
     */
    @Transactional
    public AdminUserResponse suspend(UUID adminId, UUID userId, String reason) {
        User user = findUser(userId);
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
        Map<String, Object> details = new java.util.LinkedHashMap<>();
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
     * approuve ou de {@link #verifyIdentity}. Le dossier eventuel passe REJECTED avec le
     * motif, pour que l utilisateur puisse resoumettre ; il est prevenu (IDENTITY_REVOKED).
     */
    @Transactional
    public AdminUserResponse revokeIdentity(UUID adminId, UUID userId, String reason) {
        User user = findUser(userId);
        boolean hadBadge = user.isIdentityVerified();
        user.setIdentityVerified(false);
        user = userRepository.save(user);
        Map<String, Object> details = new java.util.LinkedHashMap<>();
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

    /** Anonymisation par l administration (constat F507), voir {@link UserService#anonymize}. */
    @Transactional
    public AdminUserResponse anonymize(UUID adminId, UUID userId, String reason) {
        userService.anonymize(userId, adminId, reason);
        return toResponse(findUser(userId));
    }

    @Transactional
    public AdminUserResponse verifyIdentity(UUID adminId, UUID userId) {
        User user = findUser(userId);
        user.setIdentityVerified(true);
        user = userRepository.save(user);
        auditService.log(adminId, "USER_IDENTITY_VERIFIED", "user", userId, Map.of());
        return toResponse(user);
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

    private AdminUserResponse toResponse(User u) {
        long tripsPublished = tripRepository.countByDriverId(u.getId());
        long bookingsMade = bookingRepository.countByPassengerId(u.getId());
        return new AdminUserResponse(u.getId(), u.getFirstName(), u.getLastName(), u.getPhone(), u.getEmail(),
                u.getCreatedAt(), u.isIdentityVerified(), u.isPhoneVerified(), u.getStatus() == UserStatus.SUSPENDED,
                tripsPublished, bookingsMade, u.getRatingAvg(), u.getDeletedAt());
    }
}
