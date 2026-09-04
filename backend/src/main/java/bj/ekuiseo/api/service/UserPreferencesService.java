package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.UserPreferences;
import bj.ekuiseo.api.dto.user.UpdateUserPreferencesRequest;
import bj.ekuiseo.api.dto.user.UserPreferencesResponse;
import bj.ekuiseo.api.repository.UserPreferencesRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Preferences de notification et preferences a bord (regle metier n.17). Une
 * ligne par utilisateur, creee paresseusement (a la premiere consultation ou
 * modification) plutot qu'a l'inscription, pour ne pas alourdir AuthService.
 * L'absence de ligne equivaut donc aux valeurs par defaut (voir UserPreferences).
 */
@Service
public class UserPreferencesService {

    private final UserPreferencesRepository userPreferencesRepository;
    private final UserRepository userRepository;

    public UserPreferencesService(UserPreferencesRepository userPreferencesRepository, UserRepository userRepository) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public UserPreferencesResponse get(UUID userId) {
        return toResponse(findOrCreate(userId));
    }

    @Transactional
    public UserPreferencesResponse update(UUID userId, UpdateUserPreferencesRequest req) {
        UserPreferences prefs = findOrCreate(userId);
        if (req.notifyByPush() != null) prefs.setNotifyByPush(req.notifyByPush());
        if (req.notifyBySms() != null) prefs.setNotifyBySms(req.notifyBySms());
        if (req.notifyByEmail() != null) prefs.setNotifyByEmail(req.notifyByEmail());
        if (req.language() != null) prefs.setLanguage(req.language());
        if (req.smoking() != null) prefs.setSmoking(req.smoking());
        if (req.music() != null) prefs.setMusic(req.music());
        if (req.pets() != null) prefs.setPets(req.pets());
        if (req.chatty() != null) prefs.setChatty(req.chatty());
        return toResponse(userPreferencesRepository.save(prefs));
    }

    private UserPreferences findOrCreate(UUID userId) {
        return userPreferencesRepository.findByUserId(userId).orElseGet(() -> {
            User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
            return userPreferencesRepository.save(UserPreferences.builder().user(user).build());
        });
    }

    private UserPreferencesResponse toResponse(UserPreferences p) {
        return new UserPreferencesResponse(p.isNotifyByPush(), p.isNotifyBySms(), p.isNotifyByEmail(), p.getLanguage(),
                p.isSmoking(), p.isMusic(), p.isPets(), p.getChatty());
    }
}
