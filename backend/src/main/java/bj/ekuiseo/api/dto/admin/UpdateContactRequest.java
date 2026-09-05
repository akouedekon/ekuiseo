package bj.ekuiseo.api.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Correction de contact par un administrateur (PATCH /api/v1/admin/users/{id}/contact) :
 * e-mail et/ou numero, avec motif obligatoire journalise. Reserve aux dossiers ou
 * l identite du demandeur a ete verifiee hors ligne (constat F537 de l audit).
 */
public record UpdateContactRequest(
        @Email @Size(max = 255) String email,
        @Size(max = 25) String phone,
        @NotBlank(message = "Le motif est obligatoire") @Size(max = 500) String reason
) {
}
