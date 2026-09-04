package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.review.ReviewResponse;
import bj.ekuiseo.api.dto.user.PublicUserProfileResponse;
import bj.ekuiseo.api.service.ReviewService;
import bj.ekuiseo.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Utilisateurs (public)", description = "Profil public et avis, consultables sans authentification")
@RestController
@RequestMapping("/api/v1/users")
public class UserPublicController {

    private final ReviewService reviewService;
    private final UserService userService;

    public UserPublicController(ReviewService reviewService, UserService userService) {
        this.reviewService = reviewService;
        this.userService = userService;
    }

    @Operation(summary = "Profil public d'un utilisateur", description = "Nom, photo, note, badges de verification, anciennete, vehicules, statistiques publiques. Jamais de telephone/e-mail/date de naissance (regle metier n.16).")
    @GetMapping("/{id}")
    public PublicUserProfileResponse getProfile(@PathVariable UUID id) {
        return userService.getPublicProfile(id);
    }

    @Operation(summary = "Avis recus par un utilisateur")
    @GetMapping("/{id}/reviews")
    public List<ReviewResponse> reviews(@PathVariable UUID id) {
        return reviewService.reviewsForUser(id);
    }
}
