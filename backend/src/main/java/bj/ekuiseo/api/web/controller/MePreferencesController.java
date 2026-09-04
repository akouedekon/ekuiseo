package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.user.UpdateUserPreferencesRequest;
import bj.ekuiseo.api.dto.user.UserPreferencesResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.UserPreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Preferences de notification et preferences a bord de l'utilisateur connecte (regle metier n.17). */
@Tag(name = "Mes preferences", description = "Notifications et preferences a bord (musique, fumeur, bagages...)")
@RestController
@RequestMapping("/api/v1/me/preferences")
public class MePreferencesController {

    private final UserPreferencesService userPreferencesService;
    private final CurrentUser currentUser;

    public MePreferencesController(UserPreferencesService userPreferencesService, CurrentUser currentUser) {
        this.userPreferencesService = userPreferencesService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mes preferences")
    @GetMapping
    public UserPreferencesResponse get() {
        return userPreferencesService.get(currentUser.id());
    }

    @Operation(summary = "Modifier mes preferences (PATCH partiel)")
    @PatchMapping
    public UserPreferencesResponse update(@RequestBody UpdateUserPreferencesRequest req) {
        return userPreferencesService.update(currentUser.id(), req);
    }
}
