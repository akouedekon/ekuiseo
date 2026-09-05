package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.DriverPayout;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.domain.enums.TripStatus;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.trip.VehicleSummary;
import bj.ekuiseo.api.dto.user.PublicPreferencesResponse;
import bj.ekuiseo.api.dto.user.PublicUserProfileResponse;
import bj.ekuiseo.api.dto.user.UpdateMeRequest;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.dto.user.VehicleRequest;
import bj.ekuiseo.api.dto.user.VehicleResponse;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.mapper.VehicleMapper;
import bj.ekuiseo.api.repository.BookingRepository;
import bj.ekuiseo.api.repository.DriverPayoutRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.NotificationRepository;
import bj.ekuiseo.api.repository.PaymentAccountRepository;
import bj.ekuiseo.api.repository.SearchAlertRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    /** Regle metier n.22 : sous ce seuil de trajets/echanges mesurables, on renvoie null plutot qu'un pourcentage/delai trompeur. */
    private static final int MIN_SAMPLE_SIZE = 5;
    private static final Duration RESPONSE_TIME_WINDOW = Duration.ofDays(90);

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final MessageRepository messageRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final PaymentAccountRepository paymentAccountRepository;
    private final SearchAlertRepository searchAlertRepository;
    private final NotificationRepository notificationRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final DriverPayoutRepository driverPayoutRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final UserMapper userMapper;
    private final VehicleMapper vehicleMapper;

    public UserService(UserRepository userRepository, VehicleRepository vehicleRepository, TripRepository tripRepository,
                        BookingRepository bookingRepository, MessageRepository messageRepository,
                        UserPreferencesRepository userPreferencesRepository,
                        PaymentAccountRepository paymentAccountRepository, SearchAlertRepository searchAlertRepository,
                        NotificationRepository notificationRepository,
                        IdentityVerificationRepository identityVerificationRepository,
                        DriverPayoutRepository driverPayoutRepository, RefreshTokenService refreshTokenService,
                        AuditService auditService, UserMapper userMapper, VehicleMapper vehicleMapper) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.messageRepository = messageRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.paymentAccountRepository = paymentAccountRepository;
        this.searchAlertRepository = searchAlertRepository;
        this.notificationRepository = notificationRepository;
        this.identityVerificationRepository = identityVerificationRepository;
        this.driverPayoutRepository = driverPayoutRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.userMapper = userMapper;
        this.vehicleMapper = vehicleMapper;
    }

    // ------------------------------------------------------------------
    // Suppression de compte (constat F507) : anonymisation, pas suppression
    // physique. Les reservations, paiements et avis restent (obligations
    // comptables et litiges, docs/CONFORMITE.md 3.2), rattaches a un compte
    // vide de toute donnee personnelle.
    // ------------------------------------------------------------------

    /** Prenom / nom affiches a la place de ceux d un compte supprime (avis, messages, historiques). */
    public static final String DELETED_FIRST_NAME = "Utilisateur";
    public static final String DELETED_LAST_NAME = "supprime";
    static final String DELETED_MESSAGE_BODY = "[message supprime]";
    static final String DELETED_PLATE = "********";
    private static final List<TripStatus> UPCOMING_TRIP_STATUSES = List.of(TripStatus.PUBLISHED, TripStatus.FULL);
    private static final List<BookingStatus> ACTIVE_BOOKING_STATUSES = List.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
    private static final List<PayoutStatus> OPEN_PAYOUT_STATUSES = List.of(PayoutStatus.PENDING, PayoutStatus.PROCESSING);

    /**
     * Verifie qu un compte peut etre anonymise sans laisser d engagement en plan : aucun
     * trajet PUBLISHED/FULL a venir ni navette (TEMPLATE) active en tant que conducteur,
     * aucune reservation PENDING_PAYMENT/CONFIRMED en tant que passager, aucun reversement
     * PENDING/PROCESSING. 409 avec un detail explicite sinon. Appelee avant l envoi du code
     * de confirmation (l utilisateur apprend l obstacle tout de suite) et a nouveau dans
     * {@link #anonymize}.
     */
    @Transactional(readOnly = true)
    public void assertCanBeAnonymized(UUID userId) {
        User user = findUser(userId);
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ConflictException("Ce compte a deja ete supprime");
        }
        Instant now = Instant.now();
        if (!tripRepository.findByDriverIdAndStatusInAndDepartureAtAfter(userId, UPCOMING_TRIP_STATUSES, now).isEmpty()) {
            throw new ConflictException("Impossible de supprimer le compte : vous avez un trajet a venir. "
                    + "Annulez-le d abord depuis Mes trajets.");
        }
        if (tripRepository.countByDriverIdAndStatus(userId, TripStatus.TEMPLATE) > 0) {
            throw new ConflictException("Impossible de supprimer le compte : vous avez une navette quotidienne active. "
                    + "Arretez-la d abord depuis Mes trajets.");
        }
        if (bookingRepository.existsByPassengerIdAndStatusIn(userId, ACTIVE_BOOKING_STATUSES)) {
            throw new ConflictException("Impossible de supprimer le compte : vous avez une reservation en cours. "
                    + "Annulez-la d abord ou attendez la fin du trajet.");
        }
        if (driverPayoutRepository.existsByDriverIdAndStatusIn(userId, OPEN_PAYOUT_STATUSES)) {
            throw new ConflictException("Impossible de supprimer le compte : un reversement vous est encore du. "
                    + "Il sera verse avant la suppression ; reessayez ensuite.");
        }
    }

    /**
     * Anonymise le compte (droit a l effacement) : telephone remplace par un numero
     * factice unique derive de l identifiant ({@code +999} + 12 chiffres, conforme a
     * chk_users_phone_e164), e-mail efface, identite reduite a "Utilisateur supprime",
     * photo/bio/motif de suspension/abonnement push effaces, mot de passe rendu
     * inutilisable ; comptes mobile money, alertes, notifications, preferences et dossier
     * d identite supprimes ; plaques des vehicules masquees (lignes conservees pour les
     * trajets passes) ; destination des reversements soldes masquee ; corps des messages
     * envoyes remplace ; reservations, paiements et avis conserves ; toutes les sessions
     * revoquees. Statut DELETED, journalise USER_ANONYMIZED.
     *
     * @param actorId auteur de l action : l utilisateur lui-meme ou un administrateur
     * @param reason  motif (obligatoire cote administration, libre cote utilisateur)
     */
    @Transactional
    public void anonymize(UUID userId, UUID actorId, String reason) {
        assertCanBeAnonymized(userId);
        User user = findUser(userId);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("reason", reason == null ? "" : reason);
        audit.put("self", userId.equals(actorId));
        audit.put("previousStatus", user.getStatus().name());
        audit.put("previousPhone", Masking.phone(user.getPhone()));
        audit.put("previousEmail", Masking.email(user.getEmail()));

        String anonymousPhone = anonymousPhone(userId);
        if (userRepository.existsByPhoneAndIdNot(anonymousPhone, userId)) {
            throw new ConflictException("Numero d anonymisation deja pris : reessayez");
        }
        user.setPhone(anonymousPhone);
        user.setEmail(null);
        user.setPendingEmail(null);
        user.setPhoneVerified(false);
        user.setEmailVerified(false);
        user.setIdentityVerified(false);
        user.setFirstName(DELETED_FIRST_NAME);
        user.setLastName(DELETED_LAST_NAME);
        user.setPasswordHash("deleted-" + UUID.randomUUID());
        user.setPhotoUrl(null);
        user.setBio(null);
        user.setBirthDate(null);
        user.setGenderPrefNote(null);
        user.setSuspendedReason(null);
        user.setPushSubscription(null);
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(Instant.now());
        userRepository.save(user);

        paymentAccountRepository.deleteByUserId(userId);
        searchAlertRepository.deleteByUserId(userId);
        notificationRepository.deleteByUserId(userId);
        userPreferencesRepository.findByUserId(userId).ifPresent(userPreferencesRepository::delete);
        identityVerificationRepository.deleteByUserId(userId);

        int vehicles = 0;
        for (Vehicle vehicle : vehicleRepository.findByOwnerId(userId)) {
            vehicle.setPlate(DELETED_PLATE);
            vehicle.setPhotoUrl(null);
            vehicleRepository.save(vehicle);
            vehicles++;
        }
        int payouts = 0;
        for (DriverPayout payout : driverPayoutRepository.findByDriverIdOrderByRequestedAtDesc(userId)) {
            if (payout.getDestinationMsisdn() != null) {
                payout.setDestinationMsisdn(Masking.phone(payout.getDestinationMsisdn()));
                driverPayoutRepository.save(payout);
                payouts++;
            }
        }
        int messages = messageRepository.redactBySender(userId, DELETED_MESSAGE_BODY);
        int sessions = refreshTokenService.revokeAll(userId);

        audit.put("vehiclesMasked", vehicles);
        audit.put("payoutsMasked", payouts);
        audit.put("messagesRedacted", messages);
        audit.put("sessionsRevoked", sessions);
        auditService.log(actorId, "USER_ANONYMIZED", "user", userId, audit);
    }

    /** {@code +999} suivi de 12 chiffres derives de l identifiant : 15 chiffres, E.164 valide, unique par construction. */
    static String anonymousPhone(UUID userId) {
        long mixed = userId.getMostSignificantBits() ^ Long.rotateLeft(userId.getLeastSignificantBits(), 17);
        long digits = Math.floorMod(mixed, 1_000_000_000_000L);
        return "+999" + String.format("%012d", digits);
    }

    /**
     * Profil public d'un conducteur/passager (regle metier n.16) : nom, photo, note,
     * badges de verification, anciennete, vehicule(s), statistiques publiques.
     * N'expose JAMAIS le telephone, l'e-mail ni la date de naissance (voir UserResponse
     * pour le profil prive complet, reserve a /api/v1/me).
     *
     * <p>{@code reliabilityRate} et {@code responseTimeMinutes} (regle metier n.22)
     * viennent chacun d'une seule requete d'agregation SQL (voir BookingRepository#
     * getReliabilityStats et MessageRepository#getResponseTimeStats) - jamais du
     * chargement d'une collection de reservations ou de messages en memoire, ce
     * profil etant consulte frequemment (avant chaque reservation).</p>
     */
    @Transactional(readOnly = true)
    public PublicUserProfileResponse getPublicProfile(UUID userId) {
        User user = findUser(userId);
        List<VehicleSummary> vehicles = vehicleRepository.findByOwnerId(userId).stream()
                .map(vehicleMapper::toSummary).toList();
        long tripsCompleted = tripRepository.countByDriverIdAndStatus(userId, TripStatus.COMPLETED);
        return new PublicUserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getPhotoUrl(),
                user.getBio(), user.getRatingAvg(), user.getRatingCount(), user.isPhoneVerified(), user.isIdentityVerified(),
                user.getCreatedAt(), tripsCompleted, vehicles,
                computeReliabilityRate(userId), computeResponseTimeMinutes(userId), resolvePublicPreferences(userId));
    }

    /**
     * % entier de trajets honores = COMPLETED / (COMPLETED + annulations conducteur
     * tardives + NO_SHOW), {@code null} en dessous de {@link #MIN_SAMPLE_SIZE} trajets
     * mesurables - pour ne pas afficher "0 %" a un conducteur qui vient d'arriver
     * (le front doit distinguer "pas encore d'historique" de "mauvais historique").
     */
    private Integer computeReliabilityRate(UUID driverId) {
        BookingRepository.DriverReliabilityStats stats = bookingRepository.getReliabilityStats(driverId);
        long denominator = stats.getCompleted() + stats.getNoShow() + stats.getLateCancelledByDriver();
        if (denominator < MIN_SAMPLE_SIZE) {
            return null;
        }
        return (int) Math.round(100.0 * stats.getCompleted() / denominator);
    }

    /**
     * Delai median (minutes) de premiere reponse du conducteur a ses passagers, 90
     * derniers jours, {@code null} en dessous de {@link #MIN_SAMPLE_SIZE} echanges
     * mesurables.
     */
    private Integer computeResponseTimeMinutes(UUID driverId) {
        Instant since = Instant.now().minus(RESPONSE_TIME_WINDOW);
        MessageRepository.DriverResponseTimeStats stats = messageRepository.getResponseTimeStats(driverId, since);
        if (stats.getSampleSize() < MIN_SAMPLE_SIZE || stats.getMedianMinutes() == null) {
            return null;
        }
        return (int) Math.round(stats.getMedianMinutes());
    }

    /**
     * Preferences a bord PUBLIQUES uniquement (jamais les preferences de notification,
     * privees). Contrairement a UserPreferencesService#findOrCreate (appele sur SON
     * PROPRE profil), ne cree jamais de ligne {@code user_preferences} : consulter le
     * profil d'un tiers ne doit pas avoir d'effet de bord en base. Absence de ligne =
     * valeurs par defaut (regle metier n.17, memes valeurs que UserPreferences).
     */
    private PublicPreferencesResponse resolvePublicPreferences(UUID userId) {
        UserPreferences prefs = userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> UserPreferences.builder().build());
        return new PublicPreferencesResponse(prefs.isSmoking(), prefs.isMusic(), prefs.isPets(), prefs.getChatty());
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(UUID userId) {
        return userMapper.toResponse(findUser(userId));
    }

    @Transactional
    public UserResponse updateMe(UUID userId, UpdateMeRequest req) {
        User user = findUser(userId);
        if (req.firstName() != null) user.setFirstName(req.firstName());
        if (req.lastName() != null) user.setLastName(req.lastName());
        if (req.bio() != null) user.setBio(req.bio());
        if (req.photoUrl() != null) user.setPhotoUrl(req.photoUrl());
        return userMapper.toResponse(userRepository.save(user));
    }

    @Transactional
    public VehicleResponse addVehicle(UUID ownerId, VehicleRequest req) {
        User owner = findUser(ownerId);
        Vehicle vehicle = Vehicle.builder()
                .owner(owner)
                .brand(req.brand())
                .model(req.model())
                .color(req.color())
                .plate(req.plate())
                .seats(req.seats())
                .comfortLevel(req.comfortLevel())
                .photoUrl(req.photoUrl())
                .verified(false)
                .build();
        return vehicleMapper.toResponse(vehicleRepository.save(vehicle));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listVehicles(UUID ownerId) {
        return vehicleRepository.findByOwnerId(ownerId).stream().map(vehicleMapper::toResponse).toList();
    }

    /**
     * DELETE /api/v1/me/vehicles/{id}. Refuse (409) si le vehicule est engage sur
     * un trajet a venir non annule (PUBLISHED ou FULL, departure_at dans le futur) :
     * le supprimer casserait la reference trip.vehicle_id d'un trajet actif.
     */
    @Transactional
    public void deleteVehicle(UUID ownerId, UUID vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicule introuvable"));
        if (!vehicle.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("Ce vehicule ne vous appartient pas");
        }
        boolean engaged = tripRepository.existsByVehicleIdAndStatusInAndDepartureAtAfter(
                vehicleId, List.of(TripStatus.PUBLISHED, TripStatus.FULL), Instant.now());
        if (engaged) {
            throw new ConflictException("Ce vehicule est engage sur un trajet a venir et ne peut pas etre supprime");
        }
        vehicleRepository.delete(vehicle);
    }

    private User findUser(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }
}
