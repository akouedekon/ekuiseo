package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.conversation.ConversationSummary;
import bj.ekuiseo.api.dto.trip.RecurringTripResponse;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.dto.user.UpdateMeRequest;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.dto.user.VehicleRequest;
import bj.ekuiseo.api.dto.user.VehicleResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.TripService;
import bj.ekuiseo.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Mon compte", description = "Profil prive, vehicules et trajets de l'utilisateur connecte")
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;
    private final TripService tripService;
    private final BookingService bookingService;
    private final MessageService messageService;
    private final CurrentUser currentUser;

    public MeController(UserService userService, TripService tripService, BookingService bookingService,
                         MessageService messageService, CurrentUser currentUser) {
        this.userService = userService;
        this.tripService = tripService;
        this.bookingService = bookingService;
        this.messageService = messageService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mon profil (prive)", description = "Inclut telephone et e-mail, contrairement au profil public (GET /api/v1/users/{id}).")
    @GetMapping
    public UserResponse getMe() {
        return userService.getMe(currentUser.id());
    }

    @Operation(summary = "Mettre a jour mon profil")
    @PatchMapping
    public UserResponse updateMe(@RequestBody UpdateMeRequest req) {
        return userService.updateMe(currentUser.id(), req);
    }

    @Operation(summary = "Ajouter un vehicule")
    @PostMapping("/vehicles")
    public ResponseEntity<VehicleResponse> addVehicle(@Valid @RequestBody VehicleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.addVehicle(currentUser.id(), req));
    }

    @Operation(summary = "Mes vehicules")
    @GetMapping("/vehicles")
    public List<VehicleResponse> listVehicles() {
        return userService.listVehicles(currentUser.id());
    }

    @Operation(summary = "Supprimer un vehicule", description = "Refuse si le vehicule est engage sur un trajet a venir (PUBLISHED ou FULL).")
    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable UUID id) {
        userService.deleteVehicle(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mes trajets (en tant que conducteur)")
    @GetMapping("/trips")
    public List<TripResponse> myTrips() {
        return tripService.myTrips(currentUser.id());
    }

    @Operation(summary = "Mon trajet de la semaine", description = "Axes empruntes au moins deux fois (detection heuristique sur l'historique de reservations, voir BookingService#myRecurringTrips).")
    @GetMapping("/recurring-trips")
    public List<RecurringTripResponse> recurringTrips() {
        return bookingService.myRecurringTrips(currentUser.id());
    }

    @Operation(summary = "Mes conversations", description = "Une conversation par reservation, avec dernier message et compteur de non-lus.")
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        return messageService.myConversations(currentUser.id());
    }
}
