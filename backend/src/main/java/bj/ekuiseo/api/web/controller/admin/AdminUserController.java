package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.dto.admin.AdminReasonRequest;
import bj.ekuiseo.api.dto.admin.AdminUserDetailResponse;
import bj.ekuiseo.api.dto.admin.AdminUserResponse;
import bj.ekuiseo.api.dto.admin.SuspendUserRequest;
import bj.ekuiseo.api.dto.admin.UpdateContactRequest;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.payment.AdminPaymentResponse;
import bj.ekuiseo.api.dto.trip.TripResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Gestion des utilisateurs cote back-office : recherche, fiche detaillee, suspension,
 * reactivation, correction de contact, retrait du badge d identite, anonymisation.
 * Reserve a ROLE_ADMIN. L ancien POST /{id}/verify-identity a ete retire (constat F604) :
 * le badge ne se pose que par l approbation d un dossier (/api/v1/admin/verifications).
 */
@Tag(name = "Admin - Utilisateurs", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;

    public AdminUserController(AdminUserService adminUserService, CurrentUser currentUser) {
        this.adminUserService = adminUserService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Rechercher des utilisateurs", description = "Recherche libre sur nom/prenom/telephone/e-mail ; liste a plat, plafonnee a 100 resultats (pas de pagination, voir AdminUserService).")
    @GetMapping
    public List<AdminUserResponse> search(@RequestParam(defaultValue = "") String q) {
        return adminUserService.search(q);
    }

    @Operation(summary = "Fiche d un utilisateur", description = "Identite (statut, type, 4 derniers caracteres de la piece), vehicules, comptes mobile money, compteurs, motif de suspension. Les reservations, trajets et paiements sont pagines sous /bookings, /trips, /payments.")
    @GetMapping("/{id}")
    public AdminUserDetailResponse detail(@PathVariable UUID id) {
        return adminUserService.getDetail(id);
    }

    @Operation(summary = "Reservations d un utilisateur (passager)", description = "Page Spring, plus recentes d abord.")
    @GetMapping("/{id}/bookings")
    public Page<BookingResponse> bookings(@PathVariable UUID id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return adminUserService.bookings(id, page, size);
    }

    @Operation(summary = "Trajets d un utilisateur (conducteur)", description = "Page Spring, departs les plus recents d abord.")
    @GetMapping("/{id}/trips")
    public Page<TripResponse> trips(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return adminUserService.trips(id, page, size);
    }

    @Operation(summary = "Paiements d un utilisateur", description = "Reservations (passager) et abonnements (conducteur), Page Spring, plus recents d abord.")
    @GetMapping("/{id}/payments")
    public Page<AdminPaymentResponse> payments(@PathVariable UUID id,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return adminUserService.payments(id, page, size);
    }

    @Operation(summary = "Suspendre un utilisateur", description = "409 pour soi-meme ou pour un autre administrateur (constat F304).")
    @PostMapping("/{id}/suspend")
    public AdminUserResponse suspend(@PathVariable UUID id, @Valid @RequestBody SuspendUserRequest req) {
        return adminUserService.suspend(currentUser.id(), id, req.reason());
    }

    @Operation(summary = "Reactiver un utilisateur suspendu")
    @PostMapping("/{id}/reinstate")
    public AdminUserResponse reinstate(@PathVariable UUID id) {
        return adminUserService.activate(currentUser.id(), id);
    }

    @Operation(summary = "Corriger le contact d un utilisateur", description = "E-mail et/ou numero (normalise en E.164), motif obligatoire. Reserve aux demandes dont l identite a ete verifiee hors ligne. Le contact corrige repart non verifie, les sessions de l utilisateur sont revoquees, l operation est journalisee (USER_CONTACT_CHANGED). 409 si le contact est deja pris.")
    @PatchMapping("/{id}/contact")
    public AdminUserResponse updateContact(@PathVariable UUID id, @Valid @RequestBody UpdateContactRequest req) {
        return adminUserService.updateContact(currentUser.id(), id, req.email(), req.phone(), req.reason());
    }

    @Operation(summary = "Retirer le badge identite verifiee", description = "Motif obligatoire, journalise (USER_IDENTITY_REVOKED) ; le dossier eventuel passe REJECTED et l utilisateur est prevenu (IDENTITY_REVOKED).")
    @PostMapping("/{id}/revoke-identity")
    public AdminUserResponse revokeIdentity(@PathVariable UUID id, @Valid @RequestBody AdminReasonRequest req) {
        return adminUserService.revokeIdentity(currentUser.id(), id, req.reason());
    }

    @Operation(summary = "Anonymiser un compte", description = "Droit a l effacement exerce par l administration : donnees personnelles effacees, historique financier conserve (voir UserService#anonymize). 409 si le compte a des obligations en cours (trajet a venir, reservation active, reversement en attente), pour soi-meme ou pour un administrateur. Journalise (USER_ANONYMIZED).")
    @PostMapping("/{id}/anonymize")
    public AdminUserResponse anonymize(@PathVariable UUID id, @Valid @RequestBody AdminReasonRequest req) {
        return adminUserService.anonymize(currentUser.id(), id, req.reason());
    }
}
