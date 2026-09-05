package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.ForbiddenException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.domain.Vehicle;
import bj.ekuiseo.api.domain.enums.TripStatus;
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
import bj.ekuiseo.api.repository.MessageRepository;
import bj.ekuiseo.api.repository.TripRepository;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final UserMapper userMapper;
    private final VehicleMapper vehicleMapper;

    public UserService(UserRepository userRepository, VehicleRepository vehicleRepository, TripRepository tripRepository,
                        BookingRepository bookingRepository, MessageRepository messageRepository,
                        UserPreferencesRepository userPreferencesRepository,
                        UserMapper userMapper, VehicleMapper vehicleMapper) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.messageRepository = messageRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.userMapper = userMapper;
        this.vehicleMapper = vehicleMapper;
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
