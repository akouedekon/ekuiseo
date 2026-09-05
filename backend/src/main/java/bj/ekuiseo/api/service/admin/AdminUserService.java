package bj.ekuiseo.api.service.admin;

import bj.ekuiseo.api.common.PhoneNumbers;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import bj.ekuiseo.api.service.AuditService;
import bj.ekuiseo.api.service.RefreshTokenService;
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

    public AdminUserService(UserRepository userRepository, VehicleRepository vehicleRepository,
                             TripRepository tripRepository, BookingRepository bookingRepository,
                             AuditService auditService, RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
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

    @Transactional
    public AdminUserResponse suspend(UUID adminId, UUID userId, String reason) {
        User user = findUser(userId);
        user.setStatus(UserStatus.SUSPENDED);
        user.setSuspendedReason(reason);
        user.setSuspendedAt(Instant.now());
        user = userRepository.save(user);
        // Plus aucune session ne doit se prolonger : le filtre JWT coupe les acces en cours
        // (statut verifie a chaque requete) et la revocation coupe les rafraichissements.
        refreshTokenService.revokeAll(userId);
        auditService.log(adminId, "USER_SUSPENDED", "user", userId, Map.of("reason", reason));
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
                tripsPublished, bookingsMade, u.getRatingAvg());
    }
}
