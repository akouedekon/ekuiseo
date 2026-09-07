package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import bj.ekuiseo.api.dto.user.IdentityDocumentSummary;
import bj.ekuiseo.api.dto.user.IdentityVerificationResponse;
import bj.ekuiseo.api.dto.user.SubmitIdentityRequest;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.IdentityDocumentService;
import bj.ekuiseo.api.service.IdentityVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Verification d'identite de l'utilisateur connecte (regle metier n.19) : type et numero de
 * la piece, puis les pieces elles-memes (recto, verso, selfie ; V20), chiffrees sur le serveur
 * et supprimees 30 jours apres la decision.
 */
@Tag(name = "Mon identite", description = "Depot et etat de la verification d'identite")
@RestController
@RequestMapping("/api/v1/me/identity")
public class MeIdentityController {

    private final IdentityVerificationService identityVerificationService;
    private final IdentityDocumentService identityDocumentService;
    private final CurrentUser currentUser;

    public MeIdentityController(IdentityVerificationService identityVerificationService,
                                IdentityDocumentService identityDocumentService, CurrentUser currentUser) {
        this.identityVerificationService = identityVerificationService;
        this.identityDocumentService = identityDocumentService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Etat de ma verification d'identite")
    @GetMapping
    public IdentityVerificationResponse get() {
        return identityVerificationService.get(currentUser.id());
    }

    @Operation(summary = "Soumettre ma verification d'identite", description = "Une nouvelle soumission remplace la precedente et repasse au statut PENDING.")
    @PostMapping
    public IdentityVerificationResponse submit(@Valid @RequestBody SubmitIdentityRequest req) {
        return identityVerificationService.submit(currentUser.id(), req);
    }

    @Operation(summary = "Televerser une piece (recto, verso ou selfie)",
            description = "Multipart `file` (JPEG, PNG, WebP ou PDF, 5 Mo max ; type controle par les octets). Une piece par face : "
                    + "un nouvel envoi remplace la precedente. 404 sans dossier, 409 si le dossier n est plus en attente, "
                    + "503 si le stockage n est pas active.")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IdentityDocumentSummary upload(@RequestParam IdentityDocumentSide side,
                                          @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fichier vide");
        }
        if (file.getSize() > IdentityDocumentService.MAX_SIZE_BYTES) {
            throw new BadRequestException("Fichier trop volumineux : 5 Mo au maximum");
        }
        return identityDocumentService.upload(currentUser.id(), side, file.getBytes());
    }

    @Operation(summary = "Retirer une piece", description = "Possible tant que le dossier est en attente (409 sinon).")
    @DeleteMapping("/documents/{side}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable IdentityDocumentSide side) {
        identityDocumentService.delete(currentUser.id(), side);
    }
}
