package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.admin.AdminVerificationResponse;
import bj.ekuiseo.api.dto.admin.RejectVerificationRequest;
import bj.ekuiseo.api.dto.user.IdentityDocumentSummary;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.IdentityDocumentService;
import bj.ekuiseo.api.service.admin.AdminVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * File de moderation des verifications d'identite (regle metier n.19). Reserve
 * a ROLE_ADMIN. Le parametre {@code status} (PENDING par defaut, APPROVED ou REJECTED)
 * selectionne la file a traiter ou l historique des decisions (constat F210). Les pieces
 * televersees (V20) se listent et se lisent par dossier ; chaque lecture est journalisee.
 */
@Tag(name = "Admin - Verifications d'identite", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/verifications")
public class AdminVerificationController {

    private final AdminVerificationService adminVerificationService;
    private final IdentityDocumentService identityDocumentService;
    private final CurrentUser currentUser;

    public AdminVerificationController(AdminVerificationService adminVerificationService,
                                       IdentityDocumentService identityDocumentService, CurrentUser currentUser) {
        this.adminVerificationService = adminVerificationService;
        this.identityDocumentService = identityDocumentService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Verifications par statut", description = "PENDING (defaut) : file a traiter, du plus ancien au plus recent. APPROVED / REJECTED : historique, decision la plus recente en tete. Consultation journalisee (ADMIN_VERIFICATIONS_LISTED).")
    @GetMapping
    public List<AdminVerificationResponse> list(@RequestParam(defaultValue = "PENDING") IdentityVerificationStatus status) {
        return adminVerificationService.listByStatus(currentUser.id(), status);
    }

    @Operation(summary = "Approuver une verification d'identite", description = "409 si le dossier n est plus PENDING. L utilisateur est prevenu (IDENTITY_APPROVED) ; le numero de piece est reduit a ses 4 derniers caracteres.")
    @PostMapping("/{id}/approve")
    public void approve(@PathVariable UUID id) {
        adminVerificationService.approve(currentUser.id(), id);
    }

    @Operation(summary = "Rejeter une verification d'identite", description = "Motif obligatoire (400 sinon). 409 si le dossier n est plus PENDING. Retire le badge et previent l utilisateur avec le motif (IDENTITY_REJECTED) ; le numero de piece est reduit a ses 4 derniers caracteres.")
    @PostMapping("/{id}/reject")
    public void reject(@PathVariable UUID id, @Valid @RequestBody RejectVerificationRequest req) {
        adminVerificationService.reject(currentUser.id(), id, req.reason().trim());
    }

    @Operation(summary = "Pieces televersees d un dossier", description = "Presence, type et taille par face ; le contenu se lit face par face.")
    @GetMapping("/{id}/documents")
    public List<IdentityDocumentSummary> documents(@PathVariable UUID id) {
        return identityDocumentService.listForAdmin(id);
    }

    @Operation(summary = "Contenu d une piece", description = "Flux dechiffre, affiche en ligne (Content-Disposition: inline), jamais mis en cache. Chaque lecture est journalisee (ADMIN_IDENTITY_DOCUMENT_VIEWED).")
    @GetMapping("/{id}/documents/{side}")
    public ResponseEntity<byte[]> document(@PathVariable UUID id, @PathVariable IdentityDocumentSide side) {
        IdentityDocumentService.StoredDocument doc = identityDocumentService.openForAdmin(currentUser.id(), id, side);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("identite-" + side.name().toLowerCase(Locale.ROOT) + extensionOf(doc.contentType())).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(doc.content());
    }

    static String extensionOf(String contentType) {
        switch (contentType) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case "application/pdf": return ".pdf";
            default: return "";
        }
    }
}
